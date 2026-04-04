package com.filetransfer.encryption.service;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Iterator;

/**
 * PGP encrypt / decrypt using Bouncy Castle.
 */
@Slf4j
@Service
public class PgpService {

    private static final int BUFFER_SIZE = 1 << 16;

    /**
     * Encrypt bytes with a recipient's ASCII-armored PGP public key.
     */
    public byte[] encrypt(byte[] plaintext, String armoredPublicKey) throws Exception {
        PGPPublicKey publicKey = readPublicKey(armoredPublicKey);
        ByteArrayOutputStream cipherOut = new ByteArrayOutputStream();

        try (ArmoredOutputStream armoredOut = new ArmoredOutputStream(cipherOut)) {
            PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(
                    new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                            .setWithIntegrityPacket(true)
                            .setSecureRandom(new SecureRandom())
                            .setProvider("BC")
            );
            encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(publicKey).setProvider("BC"));

            try (OutputStream encOut = encGen.open(armoredOut, new byte[BUFFER_SIZE])) {
                PGPCompressedDataGenerator compGen = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
                try (OutputStream compOut = compGen.open(encOut)) {
                    PGPLiteralDataGenerator litGen = new PGPLiteralDataGenerator();
                    try (OutputStream litOut = litGen.open(compOut, PGPLiteralData.BINARY,
                            "data", plaintext.length, new Date())) {
                        litOut.write(plaintext);
                    }
                }
            }
        }
        return cipherOut.toByteArray();
    }

    /**
     * Decrypt PGP-encrypted bytes using an ASCII-armored private key and passphrase.
     */
    public byte[] decrypt(byte[] ciphertext, String armoredPrivateKey, char[] passphrase) throws Exception {
        PGPSecretKeyRingCollection secretKeyRing = readSecretKeyRing(armoredPrivateKey);
        InputStream decoderStream = PGPUtil.getDecoderStream(new ByteArrayInputStream(ciphertext));
        PGPObjectFactory factory = new PGPObjectFactory(decoderStream, new JcaKeyFingerprintCalculator());

        PGPEncryptedDataList encList = null;
        Object obj = factory.nextObject();
        if (obj instanceof PGPEncryptedDataList list) {
            encList = list;
        } else {
            encList = (PGPEncryptedDataList) factory.nextObject();
        }

        PGPPublicKeyEncryptedData encData = null;
        PGPPrivateKey privateKey = null;

        for (var encDataObj : encList) {
            if (encDataObj instanceof PGPPublicKeyEncryptedData pked) {
                PGPSecretKey secretKey = secretKeyRing.getSecretKey(pked.getKeyID());
                if (secretKey != null) {
                    privateKey = secretKey.extractPrivateKey(
                            new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(passphrase));
                    encData = pked;
                    break;
                }
            }
        }

        if (privateKey == null || encData == null) throw new IllegalArgumentException("No matching private key found");

        InputStream clear = encData.getDataStream(
                new JcePublicKeyDataDecryptorFactoryBuilder().setProvider("BC").build(privateKey));

        PGPObjectFactory plainFact = new PGPObjectFactory(clear, new JcaKeyFingerprintCalculator());
        Object message = plainFact.nextObject();

        if (message instanceof PGPCompressedData compressed) {
            plainFact = new PGPObjectFactory(compressed.getDataStream(), new JcaKeyFingerprintCalculator());
            message = plainFact.nextObject();
        }

        if (message instanceof PGPLiteralData litData) {
            return litData.getInputStream().readAllBytes();
        }

        throw new IllegalArgumentException("Unexpected PGP message type: " + message.getClass());
    }

    private PGPPublicKey readPublicKey(String armored) throws Exception {
        try (InputStream in = PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armored.getBytes()))) {
            PGPPublicKeyRingCollection pgpPub = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
            for (PGPPublicKeyRing keyRing : pgpPub) {
                for (PGPPublicKey key : keyRing) {
                    if (key.isEncryptionKey()) return key;
                }
            }
        }
        throw new IllegalArgumentException("No encryption key found in provided public key");
    }

    private PGPSecretKeyRingCollection readSecretKeyRing(String armored) throws Exception {
        try (InputStream in = PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armored.getBytes()))) {
            return new PGPSecretKeyRingCollection(in, new JcaKeyFingerprintCalculator());
        }
    }
}
