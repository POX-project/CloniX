package dev.cloneforge.patcher.sign

import android.util.Log
import com.android.apksig.ApkSigner
import dev.cloneforge.patcher.CfConstants
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1GeneralizedTime
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 16 KB-aligned V1/V2/V3 signing engine (spec 7.2) built on Google's apksig.
 *
 * Self-signed certificate generation uses low-level BouncyCastle ASN.1
 * primitives from bcprov-jdk18on alone (JcaX509v3CertificateBuilder lives in
 * the separate bcpkix artifact, which is NOT on the module classpath) plus the
 * JDK RSA signature provider. The deprecated
 * org.bouncycastle.x509.X509V3CertificateGenerator must not be used.
 *
 * The per-target key is persisted in a BKS keystore at
 * keystoreDir/cloneforge_master.bks (alias = sanitized original package name)
 * so repeated clone builds of the same target upgrade in place with the same key.
 */
class ApkAlignAndSignEngine(private val keystoreDir: File) {

    companion object {
        private const val TAG = "CloneForgeSign"

        private const val KEY_ALGORITHM = "RSA"
        private const val KEY_SIZE = 2048
        private const val JDK_SIGNATURE_ALGORITHM = "SHA256withRSA"
        private const val VALIDITY_DAYS = 30L * 365
        private const val CLOCK_SKEW_MS = 86_400_000L
        private const val X509_VERSION_V3 = 2 // encoded value = (version - 1)
    }

    init {
        if (!keystoreDir.exists()) keystoreDir.mkdirs()
    }

    /**
     * Retrieves or generates a persistent RSA-2048 signing key for [aliasKey]
     * so future updates are signed with a consistent identity and can be
     * installed in-place without INSTALL_PARSE_FAILED_NO_CERTIFICATES.
     */
    fun getOrCreateSignerConfig(aliasKey: String): ApkSigner.SignerConfig {
        val ksFile = File(keystoreDir, CfConstants.KEYSTORE_FILE)
        val ksPassword = CfConstants.KEYSTORE_PASSWORD.toCharArray()
        // Android's default KeyStore type is BKS.
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())

        if (ksFile.isFile) {
            try {
                ksFile.inputStream().use { keyStore.load(it, ksPassword) }
            } catch (e: Exception) {
                // Corrupt keystore: rebuild it from scratch.
                Log.w(TAG, "Keystore unreadable, regenerating: ${e.message}")
                keyStore.load(null, ksPassword)
            }
        } else {
            keyStore.load(null, ksPassword)
        }

        if (!keyStore.containsAlias(aliasKey)) {
            val keyPair = generateKeyPair()
            val certificate = generateSelfSignedCertificate(keyPair)
            keyStore.setKeyEntry(aliasKey, keyPair.private, ksPassword, arrayOf(certificate))
            ksFile.outputStream().use { keyStore.store(it, ksPassword) }
        }

        val privateKey = keyStore.getKey(aliasKey, ksPassword) as PrivateKey
        val cert = keyStore.getCertificate(aliasKey) as X509Certificate

        return ApkSigner.SignerConfig.Builder("CERT", privateKey, listOf(cert)).build()
    }

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        kpg.initialize(KEY_SIZE, SecureRandom())
        return kpg.genKeyPair()
    }

    /**
     * Hand-built DER X.509 v3 self-signed certificate (SHA256/RSA):
     *
     * ```
     * Certificate ::= SEQUENCE {
     *     tbsCertificate ::= SEQUENCE {
     *         version [0] EXPLICIT INTEGER 2 (v3),
     *         serialNumber INTEGER,
     *         signature AlgorithmIdentifier sha256WithRSAEncryption,
     *         issuer Name, validity SEQUENCE { GeneralizedTime x2 },
     *         subject Name, subjectPublicKeyInfo }
     *     signatureAlgorithm ::= same AlgorithmIdentifier,
     *     signatureValue BIT STRING }
     * ```
     *
     * The JDK encoder already produces a valid SubjectPublicKeyInfo, and the
     * JDK JCA "SHA256withRSA" signature provider produces the signatureValue;
     * bcprov only supplies the ASN.1 assembly.
     */
    internal fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - CLOCK_SKEW_MS)
        val notAfter = Date(now + VALIDITY_DAYS * CLOCK_SKEW_MS)
        // X500Name(String) uses BC's default DN style; both DN strings are
        // identical so the certificate serializes with issuer == subject.
        val dn = "CN=CloneForge Engine,O=CloneForge,C=US"
        val issuer = X500Name(dn)
        val subject = X500Name(dn)
        val serial = BigInteger.valueOf(now)
        val sha256WithRSA = AlgorithmIdentifier(
            PKCSObjectIdentifiers.sha256WithRSAEncryption,
            DERNull.INSTANCE
        )
        val validityValues = ASN1EncodableVector()
        validityValues.add(ASN1GeneralizedTime(notBefore))
        validityValues.add(ASN1GeneralizedTime(notAfter))
        val validity = DERSequence(validityValues)
        val spki = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)

        val tbsEntries = ASN1EncodableVector()
        tbsEntries.add(DERTaggedObject(true, 0, ASN1Integer(X509_VERSION_V3.toLong())))
        tbsEntries.add(ASN1Integer(serial))
        tbsEntries.add(sha256WithRSA)
        tbsEntries.add(issuer)
        tbsEntries.add(validity)
        tbsEntries.add(subject)
        tbsEntries.add(spki)
        val tbsCertificate = DERSequence(tbsEntries)
        val tbsBytes = tbsCertificate.getEncoded(ASN1Encoding.DER)

        val signer = Signature.getInstance(JDK_SIGNATURE_ALGORITHM)
        signer.initSign(keyPair.private)
        signer.update(tbsBytes)
        val signatureValue = signer.sign()

        val certificateEntries = ASN1EncodableVector()
        certificateEntries.add(tbsCertificate)
        certificateEntries.add(sha256WithRSA)
        certificateEntries.add(DERBitString(signatureValue))
        val certificate = DERSequence(certificateEntries)
        val derBytes = certificate.getEncoded(ASN1Encoding.DER)

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(derBytes)) as X509Certificate
    }

    /**
     * Signs an APK (base or split) with V1 + V2 + V3 schemes. The input must
     * already be data-offset aligned by the patcher's zip mapper; apksig
     * inserts the scheme signing blocks between the last local entry and the
     * central directory, which does not disturb entry data offsets.
     */
    fun alignAndSignApk(
        unsignedApk: File,
        signedOutputApk: File,
        signerConfig: ApkSigner.SignerConfig,
        minSdkVersion: Int = 28
    ) {
        signedOutputApk.parentFile?.mkdirs()
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedOutputApk)
            .setMinSdkVersion(minSdkVersion)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setOtherSignersSignaturesPreserved(false)
            .build()

        signer.sign()
    }
}
