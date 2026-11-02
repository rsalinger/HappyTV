package com.rysal.happytv.security;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import android.security.keystore.KeyGenParameterSpec;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * JCA double for Android's {@code AndroidKeyStore} provider, which Robolectric
 * does not ship: {@code KeyStore.getInstance("AndroidKeyStore")} throws
 * {@link java.security.NoSuchAlgorithmException} under Robolectric — which is
 * why every EncryptedSharedPreferences-backed store test dies there.
 *
 * Installed as a normal {@link Provider} so MasterKeys and Tink resolve it
 * through ordinary JCA lookup: {@code KeyStore.getInstance("AndroidKeyStore")}
 * and {@code KeyGenerator.getInstance("AES", "AndroidKeyStore")}.
 *
 * One behavioural quirk worth knowing: on Android a KeyGenerator initialized
 * with an AndroidKeyStore {@link KeyGenParameterSpec} writes the new key into
 * the keystore under {@code spec.getKeystoreAlias()} — no {@code setEntry} call
 * happens at all. {@link KeyGeneratorSpiImpl#engineGenerateKey} reproduces that
 * by publishing into {@link #KEYS} under the alias it was initialized with.
 *
 * Test-only. No production code may reference this class.
 */
public final class FakeAndroidKeyStore {

    /** Alias → key. Shared by every KeyStore view of the fake. */
    static final Map<String, SecretKey> KEYS = new ConcurrentHashMap<>();

    private FakeAndroidKeyStore() {
    }

    /** Idempotent — safe to call from every test class. */
    public static synchronized void install() {
        if (Security.getProvider("AndroidKeyStore") == null) {
            Security.addProvider(new FakeAndroidKeyStoreProvider());
        }
    }

    public static class FakeAndroidKeyStoreProvider extends Provider {
        public FakeAndroidKeyStoreProvider() {
            // (String, double, String): the overload visible on the unit-test
            // compile classpath (android.jar); the (String, String, String)
            // ctor only exists on the JDK side.
            super("AndroidKeyStore", 1.0, "Fake AndroidKeyStore so Robolectric can run AndroidX Security");
            put("KeyStore.AndroidKeyStore", KeyStoreSpiImpl.class.getName());
            put("KeyGenerator.AES", KeyGeneratorSpiImpl.class.getName());
        }
    }

    public static class KeyStoreSpiImpl extends KeyStoreSpi {
        @Override
        public Key engineGetKey(String alias, char[] password) {
            return KEYS.get(alias);
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            return null;
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            return null;
        }

        @Override
        public Date engineGetCreationDate(String alias) {
            return new Date();
        }

        @Override
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
            KEYS.put(alias, (SecretKey) key);
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
            throw new UnsupportedOperationException("fake keystore only stores SecretKeys");
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate cert) {
            throw new UnsupportedOperationException("fake keystore stores no certificates");
        }

        @Override
        public void engineDeleteEntry(String alias) {
            KEYS.remove(alias);
        }

        @Override
        public java.util.Enumeration<String> engineAliases() {
            return Collections.enumeration(KEYS.keySet());
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            return KEYS.containsKey(alias);
        }

        @Override
        public int engineSize() {
            return KEYS.size();
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            return KEYS.containsKey(alias);
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            return false;
        }

        @Override
        public String engineGetCertificateAlias(Certificate cert) {
            return null;
        }

        @Override
        public void engineStore(OutputStream stream, char[] password) {
            // in-memory only
        }

        @Override
        public void engineLoad(InputStream stream, char[] password) {
            // in-memory only
        }

        @Override
        public void engineLoad(KeyStore.LoadStoreParameter param) {
            // in-memory only — callers pass null (AndroidKeyStore has no file)
        }

        @Override
        public KeyStore.Entry engineGetEntry(String alias, KeyStore.ProtectionParameter protParam) {
            SecretKey key = KEYS.get(alias);
            return key == null ? null : new KeyStore.SecretKeyEntry(key);
        }
    }

    public static class KeyGeneratorSpiImpl extends KeyGeneratorSpi {
        private String alias;
        private int keySizeBits = 256;
        private SecureRandom random = new SecureRandom();

        @Override
        protected void engineInit(SecureRandom random) {
            if (random != null) {
                this.random = random;
            }
        }

        @Override
        protected void engineInit(int keysize, SecureRandom random) {
            if (keysize > 0) {
                this.keySizeBits = keysize;
            }
            if (random != null) {
                this.random = random;
            }
        }

        @Override
        protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidAlgorithmParameterException {
            if (random != null) {
                this.random = random;
            }
            if (params instanceof KeyGenParameterSpec) {
                KeyGenParameterSpec spec = (KeyGenParameterSpec) params;
                this.alias = spec.getKeystoreAlias();
                if (spec.getKeySize() > 0) {
                    this.keySizeBits = spec.getKeySize();
                }
            }
        }

        @Override
        protected SecretKey engineGenerateKey() {
            byte[] raw = new byte[Math.max(keySizeBits, 128) / 8];
            random.nextBytes(raw);
            SecretKey key = new SecretKeySpec(raw, "AES");
            if (alias != null) {
                // AndroidKeyStore writes the generated key straight into the store.
                KEYS.put(alias, key);
            }
            return key;
        }
    }
}
