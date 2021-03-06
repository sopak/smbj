/*
 * Copyright (C)2016 - SMBJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hierynomus.smbj.auth;

import com.hierynomus.ntlm.functions.NtlmFunctions;
import com.hierynomus.ntlm.messages.*;
import com.hierynomus.protocol.commons.ByteArrayUtils;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.protocol.commons.buffer.Buffer;
import com.hierynomus.protocol.commons.buffer.Endian;
import com.hierynomus.security.SecurityProvider;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.spnego.NegTokenInit;
import com.hierynomus.spnego.NegTokenTarg;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.microsoft.MicrosoftObjectIdentifiers;
import org.bouncycastle.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Random;

public class NtlmAuthenticator implements Authenticator {
    private static final Logger logger = LoggerFactory.getLogger(NtlmAuthenticator.class);

    private static final ASN1ObjectIdentifier NTLMSSP = MicrosoftObjectIdentifiers.microsoft.branch("2.2.10");
    private SecurityProvider securityProvider;
    private Random random;

    public static class Factory implements com.hierynomus.protocol.commons.Factory.Named<Authenticator> {
        @Override
        public String getName() {
            // The OID for NTLMSSP
            return "1.3.6.1.4.1.311.2.2.10";
        }

        @Override
        public NtlmAuthenticator create() {
            return new NtlmAuthenticator();
        }
    }

    private boolean initialized = false;
    private boolean completed = false;

    @Override
    public byte[] authenticate(final AuthenticationContext context, final byte[] gssToken, Session session) throws IOException {
        if (completed) {
            return null;
        } else if (!initialized) {
            logger.info("Initialized Authentication of {} using NTLM", context.getUsername());
            NtlmNegotiate ntlmNegotiate = new NtlmNegotiate();
            initialized = true;
            return negTokenInit(ntlmNegotiate);
        } else {
            logger.debug("Received token: {}", ByteArrayUtils.printHex(gssToken));
            NtlmFunctions ntlmFunctions = new NtlmFunctions(random, securityProvider);
            NegTokenTarg negTokenTarg = new NegTokenTarg().read(gssToken);
            BigInteger negotiationResult = negTokenTarg.getNegotiationResult();
            NtlmChallenge challenge;
            try {
                challenge = (NtlmChallenge) new NtlmChallenge().read(new Buffer.PlainBuffer(negTokenTarg.getResponseToken(), Endian.LE));
            } catch (Buffer.BufferException e) {
                throw new IOException(e);
            }
            logger.debug("Received NTLM challenge from: {}", challenge.getTargetName());

            byte[] serverChallenge = challenge.getServerChallenge();
            byte[] responseKeyNT = ntlmFunctions.NTOWFv2(String.valueOf(context.getPassword()), context.getUsername(), context.getDomain());
            byte[] ntlmv2ClientChallenge = ntlmFunctions.getNTLMv2ClientChallenge(challenge.getTargetInfo());
            byte[] ntlmv2Response = ntlmFunctions.getNTLMv2Response(responseKeyNT, serverChallenge, ntlmv2ClientChallenge);
            byte[] sessionkey;

            byte[] userSessionKey = ntlmFunctions.hmac_md5(responseKeyNT, Arrays.copyOfRange(ntlmv2Response, 0, 16)); // first 16 bytes of ntlmv2Response is ntProofStr
            EnumSet<NtlmNegotiateFlag> negotiateFlags = challenge.getNegotiateFlags();
            if (negotiateFlags.contains(NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_KEY_EXCH)
                && (negotiateFlags.contains(NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_SIGN)
                || negotiateFlags.contains(NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_SEAL)
                || negotiateFlags.contains(NtlmNegotiateFlag.NTLMSSP_NEGOTIATE_ALWAYS_SIGN))
                ) {
                byte[] masterKey = new byte[16];
                random.nextBytes(masterKey);
                sessionkey = ntlmFunctions.encryptRc4(userSessionKey, masterKey);
                session.setSigningKey(masterKey);
            } else {
                sessionkey = userSessionKey;
                session.setSigningKey(sessionkey);
            }

            completed = true;

            // If NTLM v2 is used, KeyExchangeKey MUST be set to the given 128-bit SessionBaseKey value.

            // MIC (16 bytes) provided if in AvPairType is key MsvAvFlags with value & 0x00000002 is true
            Object msvAvFlags = challenge.getAvPairObject(AvId.MsvAvFlags);
            if (msvAvFlags != null && ((int) msvAvFlags & 0x00000002) > 0) {
                // MIC should be calculated
                NtlmAuthenticate resp = new NtlmAuthenticate(new byte[0], ntlmv2Response,
                    context.getUsername(), context.getDomain(), null, sessionkey, EnumWithValue.EnumUtils.toLong(negotiateFlags),
                    true
                );

                // TODO correct hash should be tested

                Buffer.PlainBuffer concatenatedBuffer = new Buffer.PlainBuffer(Endian.LE);
                concatenatedBuffer.putRawBytes(negTokenTarg.getResponseToken()); //negotiateMessage
                concatenatedBuffer.putRawBytes(challenge.getServerChallenge()); //challengeMessage
                resp.writeAutentificateMessage(concatenatedBuffer); //authentificateMessage

                byte[] mic = ntlmFunctions.hmac_md5(userSessionKey, concatenatedBuffer.getCompactData());
                resp.setMic(mic);
                return negTokenTarg(resp, negTokenTarg.getResponseToken());
            } else {
                NtlmAuthenticate resp = new NtlmAuthenticate(new byte[0], ntlmv2Response,
                    context.getUsername(), context.getDomain(), null, sessionkey, EnumWithValue.EnumUtils.toLong(negotiateFlags),
                    false
                );
                return negTokenTarg(resp, negTokenTarg.getResponseToken());
            }
        }
    }

    private byte[] negTokenInit(NtlmNegotiate ntlmNegotiate) {
        NegTokenInit negTokenInit = new NegTokenInit();
        negTokenInit.addSupportedMech(NTLMSSP);
        Buffer.PlainBuffer ntlmBuffer = new Buffer.PlainBuffer(Endian.LE);
        ntlmNegotiate.write(ntlmBuffer);
        negTokenInit.setMechToken(ntlmBuffer.getCompactData());
        Buffer.PlainBuffer negTokenBuffer = new Buffer.PlainBuffer(Endian.LE);
        negTokenInit.write(negTokenBuffer);
        return negTokenBuffer.getCompactData();
    }

    private byte[] negTokenTarg(NtlmAuthenticate resp, byte[] responseToken) {
        NegTokenTarg targ = new NegTokenTarg();
        targ.setResponseToken(responseToken);
        Buffer.PlainBuffer ntlmBuffer = new Buffer.PlainBuffer(Endian.LE);
        resp.write(ntlmBuffer);
        targ.setResponseToken(ntlmBuffer.getCompactData());
        Buffer.PlainBuffer negTokenBuffer = new Buffer.PlainBuffer(Endian.LE);
        targ.write(negTokenBuffer);
        return negTokenBuffer.getCompactData();
    }

    @Override
    public void init(SecurityProvider securityProvider, Random random) {
        this.securityProvider = securityProvider;
        this.random = random;
    }

    @Override
    public boolean supports(AuthenticationContext context) {
        return context.getClass().equals(AuthenticationContext.class);
    }

}
