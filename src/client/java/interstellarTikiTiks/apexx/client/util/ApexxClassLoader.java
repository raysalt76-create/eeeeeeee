package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.event.ItemUseListener;
import interstellarTikiTiks.apexx.client.manager.TargetManager;
import interstellarTikiTiks.apexx.client.module.Module;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LdcInsnNode;

public class ApexxClassLoader extends ClassLoader {
    private static final String[] KNOWN_NAMESPACES = { "named", "intermediary", "official" };
    private static final String OBF_RUNTIME = "interstellarTikiTiks/apexx/client/util/ObfRuntime";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String[] PARENT_FIRST_PREFIXES = {
            ItemUseListener.class.getPackageName() + ".",
            Module.class.getPackageName() + ".",
            TargetManager.class.getName(),
            AttackValidator.class.getName(),
            ModuleAccess.class.getName(),
            "j2cc."
    };
    private final Map<String, byte[]> classBytes = new ConcurrentHashMap<>();
    private final SessionClassCipher classCipher = new SessionClassCipher();
    private final Map<String, String> classParents = new ConcurrentHashMap<>();
    private final Set<String> concreteClasses = ConcurrentHashMap.newKeySet();
    // Aids' integrity initializer reads the just-defined class through this loader.
    // Keep one short-lived copy because defineAndWipe clears classBytes immediately.
    private final Map<String, byte[]> classResourceBytes = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> initializationDepth = ThreadLocal.withInitial(() -> 0);
    private final Set<String> loadedClassNames = ConcurrentHashMap.newKeySet();
    private final int sessionAsmSeed;

    public Set<String> getLoadedClasses() {
        return new LinkedHashSet<>(loadedClassNames);
    }

    /** Catalog candidates from class-file headers, without defining helpers or running initializers. */
    public Set<String> getModuleClassNames() {
        Set<String> modules = new LinkedHashSet<>();
        for (String name : concreteClasses) {
            String current = name;
            Set<String> visited = new java.util.HashSet<>();
            while (current != null && visited.add(current)) {
                if (current.equals(Module.class.getName())) { modules.add(name); break; }
                String parent = classParents.get(current);
                if (parent == null) {
                    try (InputStream input = getParent().getResourceAsStream(current.replace('.', '/') + ".class")) {
                        if (input != null) parent = new ClassReader(input).getSuperName();
                    } catch (java.io.IOException ignored) { break; }
                    if (parent != null) parent = parent.replace('/', '.');
                }
                current = parent;
            }
        }
        return modules;
    }

    public ApexxClassLoader(ClassLoader parent) {
        super(parent);
        this.sessionAsmSeed = SECURE_RANDOM.nextInt() ^ (int) System.nanoTime();
    }

    



    public void loadPlaintextZip(InputStream zipStream) throws Exception {
        loadPlaintextZip(zipStream, false);
    }

    




    public void loadPlaintextZip(InputStream zipStream, boolean append) throws Exception {
        interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.checkNow();
        if (!append) {
            clearSensitiveBytes();
            loadedClassNames.clear();
        }
        byte[] zipBytes = zipStream.readAllBytes();
        try {
            if (!populateClassesFromZip(zipBytes)) {
                throw new IllegalArgumentException("Remote bundle ZIP contained no loadable classes.");
            }
        } finally {
            Arrays.fill(zipBytes, (byte) 0);
        }
    }

    public void loadEncryptedPayload(InputStream encryptedStream, byte[] rawAesKey) throws Exception {
        interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.checkNow();
        if (rawAesKey == null || rawAesKey.length != 32) {
            throw new IllegalArgumentException("AES key must be exactly 32 bytes.");
        }
        clearSensitiveBytes();
        loadedClassNames.clear();

        byte[] encryptedBytes = encryptedStream.readAllBytes();
        byte[] decryptedBytes = null;

        try {
            decryptedBytes = decryptPayloadAesCbc(encryptedBytes, rawAesKey);
            if (!populateClassesFromZip(decryptedBytes)) {
                throw new IllegalArgumentException("Secure payload could not be loaded.");
            }
        } finally {
            Arrays.fill(encryptedBytes, (byte) 0);
            if (decryptedBytes != null) {
                Arrays.fill(decryptedBytes, (byte) 0);
            }
            Arrays.fill(rawAesKey, (byte) 0);
        }
    }

    public void loadEncryptedPayload(InputStream encryptedStream, String aesKeyBase) throws Exception {
        interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.checkNow();
        clearSensitiveBytes();
        loadedClassNames.clear();

        byte[] encryptedBytes = encryptedStream.readAllBytes();
        byte[] hashedKeyBytes = null;
        byte[] hexKeyBytes = null;

        try {
            String normalizedKey = aesKeyBase == null ? "" : aesKeyBase.trim();
            hexKeyBytes = decodeHexKeyBytes(normalizedKey);
            if (hexKeyBytes != null && tryLoadPayloadWithKey(encryptedBytes, hexKeyBytes)) {
                return;
            }

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            hashedKeyBytes = sha.digest(normalizedKey.getBytes(StandardCharsets.UTF_8));
            if (!tryLoadPayloadWithKey(encryptedBytes, hashedKeyBytes)) {
                throw new IllegalArgumentException("Secure payload could not be loaded.");
            }
        } finally {
            Arrays.fill(encryptedBytes, (byte) 0);
            if (hashedKeyBytes != null) {
                Arrays.fill(hashedKeyBytes, (byte) 0);
            }
            if (hexKeyBytes != null) {
                Arrays.fill(hexKeyBytes, (byte) 0);
            }
        }
    }

    private boolean tryLoadPayloadWithKey(byte[] encryptedBytes, byte[] keyBytes) throws Exception {
        byte[] decryptedBytes = null;
        try {
            decryptedBytes = decryptPayloadAesCbc(encryptedBytes, keyBytes);
            if (!populateClassesFromZip(decryptedBytes)) {
                clearSensitiveBytes();
                loadedClassNames.clear();
                return false;
            }
            return true;
        } catch (Exception e) {
            clearSensitiveBytes();
            loadedClassNames.clear();
            return false;
        } finally {
            if (decryptedBytes != null) {
                Arrays.fill(decryptedBytes, (byte) 0);
            }
        }
    }

    private static byte[] decryptPayloadAesCbc(byte[] encryptedBytes, byte[] keyBytes) throws Exception {
        

        if (encryptedBytes == null || encryptedBytes.length < 17) {
            throw new IllegalArgumentException("Payload too short to contain IV.");
        }
        byte[] ivBytes = null;
        byte[] cipherBytes = null;
        try {
            ivBytes = Arrays.copyOfRange(encryptedBytes, 0, 16);
            cipherBytes = Arrays.copyOfRange(encryptedBytes, 16, encryptedBytes.length);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec iv = new IvParameterSpec(ivBytes);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);
            try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(cipherBytes);
                    javax.crypto.CipherInputStream cis = new javax.crypto.CipherInputStream(bis, cipher);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = cis.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                return baos.toByteArray();
            }
        } finally {
            if (ivBytes != null) Arrays.fill(ivBytes, (byte) 0);
            if (cipherBytes != null) Arrays.fill(cipherBytes, (byte) 0);
        }
    }

    private final Map<String, byte[]> resourceBytes = new ConcurrentHashMap<>();

    private boolean populateClassesFromZip(byte[] decryptedBytes) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(decryptedBytes))) {
            int classCount = 0;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                
                byte[] data = zis.readAllBytes();
                if (entry.getName().endsWith(".class")) {
                    String className = entry.getName().replace('/', '.').substring(0, entry.getName().length() - 6);
                    byte[] header = PayloadClassCodec.decode(entry.getName(), data);
                    try {
                        ClassReader reader = new ClassReader(header);
                        if (reader.getSuperName() != null) classParents.put(className, reader.getSuperName().replace('/', '.'));
                        if ((reader.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0) concreteClasses.add(className);
                    } finally { if (header != data) Arrays.fill(header, (byte) 0); }
                    // A stamped release must never accept an accidentally unprotected class body.
                    if (interstellarTikiTiks.apexx.client.auth.BuildInfo.isKnown() && !PayloadClassCodec.isEncoded(data)) {
                        Arrays.fill(data, (byte) 0);
                        clearSensitiveBytes();
                        throw new SecurityException("Release payload contains an unencoded class body");
                    }
                    // Retain only authenticated ciphertext under this loader's fresh random key.
                    byte[] sealed;
                    try { sealed = classCipher.seal(className, data); }
                    finally { Arrays.fill(data, (byte) 0); }
                    byte[] replaced = classBytes.put(className, sealed);
                    if (replaced != null) Arrays.fill(replaced, (byte) 0);
                    loadedClassNames.add(className);
                    classCount++;
                } else {
                    resourceBytes.put(entry.getName(), data);
                }
            }
            return classCount > 0;
        }
    }

    private static byte[] decodeHexKeyBytes(String value) {
        if (value == null || value.length() != 64) {
            return null;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt((i * 2) + 1), 16);
            if (hi < 0 || lo < 0) {
                Arrays.fill(out, (byte) 0);
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (name != null && name.startsWith("/")) {
            name = name.substring(1); // Standardize resource paths to omit leading slash
        }
        String className = name != null && name.endsWith(".class")
                ? name.substring(0, name.length() - 6).replace('/', '.') : null;
        if (className != null && loadedClassNames.contains(className)) {
            // Only the signed class's initializer may obtain its one-shot integrity resource.
            // Ordinary getResourceAsStream calls cannot export payload class files.
            if (interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.isPayloadBlocked() || initializationDepth.get() == 0) return null;
            byte[] sealed = classResourceBytes.remove(name);
            if (sealed == null) return null;
            byte[] encoded = classCipher.open(className, sealed);
            try {
                byte[] decoded = PayloadClassCodec.decode(name, encoded);
                if (decoded != encoded) Arrays.fill(encoded, (byte) 0);
                return new WipingByteArrayInputStream(decoded);
            } catch (RuntimeException | Error failure) {
                Arrays.fill(encoded, (byte) 0);
                throw failure;
            } finally { Arrays.fill(sealed, (byte) 0); }
        }
        byte[] data = resourceBytes.get(name);
        if (data != null) {
            return new java.io.ByteArrayInputStream(data);
        }
        return super.getResourceAsStream(name);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (loadedClassNames.contains(name) || classBytes.containsKey(name)) {
            interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.checkNow();
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> cached = findLoadedClass(name);
            if (cached != null) {
                if (resolve) {
                    resolveClass(cached);
                }
                return cached;
            }

            Class<?> loaded;
            boolean parentFirst = shouldPreferParent(name);
            if (parentFirst) {
                try {
                    loaded = super.loadClass(name, false);
                } catch (ClassNotFoundException ignored) {
                    byte[] localBytes = classBytes.get(name);
                    if (localBytes != null) {
                        loaded = defineAndWipe(name, localBytes);
                    } else {
                        loaded = tryLoadRemapped(name);
                        if (loaded == null) {
                            loaded = tryLoadFromContext(name);
                        }
                        if (loaded == null) {
                            throw ignored;
                        }
                    }
                }
            } else {
                byte[] localBytes = classBytes.get(name);
                if (localBytes != null) {
                    loaded = defineAndWipe(name, localBytes);
                } else {
                    try {
                        loaded = super.loadClass(name, false);
                    } catch (ClassNotFoundException original) {
                        loaded = tryLoadRemapped(name);
                        if (loaded == null) {
                            loaded = tryLoadFromContext(name);
                        }
                        if (loaded == null) {
                            throw original;
                        }
                    }
                }
            }

            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private boolean shouldPreferParent(String className) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> tryLoadFromContext(String name) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null && contextClassLoader != this) {
            try {
                return Class.forName(name, false, contextClassLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }

        ClassLoader parent = getParent();
        if (parent != null && parent != contextClassLoader) {
            try {
                return Class.forName(name, false, parent);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private Class<?> tryLoadRemapped(String name) {
        try {
            net.fabricmc.loader.api.MappingResolver resolver = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getMappingResolver();
            String runtimeNamespace = resolver.getCurrentRuntimeNamespace();

            for (String sourceNamespace : KNOWN_NAMESPACES) {
                if (sourceNamespace.equals(runtimeNamespace)) {
                    continue;
                }
                try {
                    String remapped = resolver.mapClassName(sourceNamespace, name);
                    if (remapped == null || remapped.equals(name)) {
                        continue;
                    }
                    return super.loadClass(remapped, false);
                } catch (ClassNotFoundException ignored) {
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = classBytes.get(name);
        if (b == null) {
            return super.findClass(name);
        }
        return defineAndWipe(name, b);
    }

    /** Initializes on the caller's game thread; integrity resource access exists only in this scope. */
    public Class<?> initializePayloadClass(String name) throws ClassNotFoundException {
        interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.checkNow();
        initializationDepth.set(initializationDepth.get() + 1);
        try {
            return Class.forName(name, true, this);
        } finally {
            int depth = initializationDepth.get() - 1;
            if (depth == 0) initializationDepth.remove();
            else initializationDepth.set(depth);
            byte[] leftover = classResourceBytes.remove(name.replace('.', '/') + ".class");
            if (leftover != null) Arrays.fill(leftover, (byte) 0);
        }
    }

    private static volatile ProtectionDomain cachedModProtectionDomain = null;

    private static ProtectionDomain getModProtectionDomain() {
        if (cachedModProtectionDomain != null) {
            return cachedModProtectionDomain;
        }
        try {
            ProtectionDomain pd = ApexxClassLoader.class.getProtectionDomain();
            if (pd != null && pd.getCodeSource() != null && pd.getCodeSource().getLocation() != null) {
                cachedModProtectionDomain = pd;
                return cachedModProtectionDomain;
            }
        } catch (Throwable ignored) {}
        try {
            Path ownJar = LoaderFiles.resolveOwnJar();
            if (ownJar != null && Files.isRegularFile(ownJar)) {
                URL url = ownJar.toUri().toURL();
                CodeSource cs = new CodeSource(url, (java.security.cert.Certificate[]) null);
                cachedModProtectionDomain = new ProtectionDomain(cs, null);
                return cachedModProtectionDomain;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Class<?> defineAndWipe(String name, byte[] sealed) {
        interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.checkNow();
        String resourceName = name.replace('.', '/') + ".class";
        byte[] encoded = classCipher.open(name, sealed);
        byte[] bytes = null;
        byte[] resourceCopy = sealed.clone();
        try {
            bytes = PayloadClassCodec.decode(resourceName, encoded);
            interstellarTikiTiks.apexx.client.auth.JvmAgentGuard.checkNow();
            ProtectionDomain pd = getModProtectionDomain();
            Class<?> defined = (pd != null)
                    ? defineClass(name, bytes, 0, bytes.length, pd)
                    : defineClass(name, bytes, 0, bytes.length);
            byte[] replaced = classResourceBytes.put(resourceName, resourceCopy);
            if (replaced != null) Arrays.fill(replaced, (byte) 0);
            return defined;
        } catch (RuntimeException | Error failure) {
            Arrays.fill(resourceCopy, (byte) 0);
            throw failure;
        } finally {
            if (bytes != null && bytes != encoded) Arrays.fill(bytes, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
            wipeClassBytes(name);
        }
    }

    /** Releases retained encoded bodies and short-lived integrity resources on teardown. */
    public void clearSensitiveBytes() {
        for (byte[] bytes : classBytes.values()) Arrays.fill(bytes, (byte) 0);
        classBytes.clear();
        classParents.clear();
        concreteClasses.clear();
        wipeClassResourceBytes();
        for (byte[] bytes : resourceBytes.values()) Arrays.fill(bytes, (byte) 0);
        resourceBytes.clear();
        classCipher.destroy();
    }

    private void wipeClassResourceBytes() {
        for (byte[] bytes : classResourceBytes.values()) {
            Arrays.fill(bytes, (byte) 0);
        }
        classResourceBytes.clear();
    }

    private void wipeClassBytes(String className) {
        byte[] removed = classBytes.remove(className);
        if (removed != null) {
            Arrays.fill(removed, (byte) 0);
        }
    }

    private byte[] rewriteClassPerSession(String className, byte[] originalClass) {
        if (originalClass == null || originalClass.length == 0) {
            return originalClass;
        }
        if (shouldPreferParent(className) || className.endsWith(".module-info")) {
            return originalClass;
        }

        byte[] current = originalClass;
        int rounds = 2 + Math.floorMod(mixSeed(sessionAsmSeed, className.hashCode()), 2);
        for (int round = 0; round < rounds; round++) {
            byte[] rewritten = rewriteClassRound(className, current, round);
            if (rewritten == null || rewritten.length == 0) {
                return originalClass;
            }
            current = rewritten;
        }
        return current;
    }

    private byte[] rewriteClassRound(String className, byte[] input, int round) {
        try {
            ClassReader reader = new ClassReader(input);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) {
                return input;
            }

            boolean mutated = false;
            int classSeed = mixSeed(sessionAsmSeed ^ round, className.hashCode());
            Random random = new Random(classSeed * 0x9E3779B9L);

            for (MethodNode method : node.methods) {
                if (isAbstractOrNative(method) || method.instructions == null || method.instructions.size() == 0) {
                    continue;
                }

                int methodSeed = mixSeed(classSeed, (method.name + method.desc).hashCode());

                if (!isConstructor(method.name) && random.nextInt(100) < 80) {
                    insertAtMethodStart(method, buildAsmNoise(methodSeed, random.nextInt()));
                    mutated = true;
                }

                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;) {
                    AbstractInsnNode next = insn.getNext();
                    int opcode = insn.getOpcode();

                    if (isReturnOrThrow(opcode)) {
                        method.instructions.insertBefore(insn,
                                buildAsmNoise(methodSeed ^ 0x7F4A7C15, random.nextInt()));
                        mutated = true;
                    } else if ((insn instanceof MethodInsnNode || insn instanceof FieldInsnNode)
                            && random.nextInt(100) < 20) {
                        method.instructions.insertBefore(insn,
                                buildAsmNoise(methodSeed ^ 0x5F356495, random.nextInt()));
                        mutated = true;
                    }

                    insn = next;
                }
            }

            if (!mutated) {
                return input;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] result = writer.toByteArray();

            for (MethodNode method : node.methods) {
                if (method.instructions != null) {
                    method.instructions.clear();
                }
                if (method.localVariables != null) {
                    method.localVariables.clear();
                }
                if (method.tryCatchBlocks != null) {
                    method.tryCatchBlocks.clear();
                }
            }
            node.methods.clear();
            if (node.fields != null) {
                node.fields.clear();
            }
            if (node.innerClasses != null) {
                node.innerClasses.clear();
            }

            return result;
        } catch (Throwable ignored) {
            return input;
        }
    }

    private InsnList buildAsmNoise(int seedA, int seedB) {
        InsnList noise = new InsnList();
        pushInt(noise, seedA);
        pushInt(noise, seedB);
        noise.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                OBF_RUNTIME,
                "rawNoise",
                "(II)V",
                false));
        if ((seedA & 3) == 0) {
            noise.add(new InsnNode(Opcodes.NOP));
        }
        return noise;
    }

    private void insertAtMethodStart(MethodNode method, InsnList list) {
        AbstractInsnNode anchor = method.instructions.getFirst();
        while (anchor instanceof LabelNode || anchor instanceof LineNumberNode || anchor instanceof FrameNode) {
            anchor = anchor.getNext();
        }
        if (anchor == null) {
            method.instructions.add(list);
        } else {
            method.instructions.insertBefore(anchor, list);
        }
    }

    private boolean isAbstractOrNative(MethodNode method) {
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0;
    }

    private boolean isConstructor(String methodName) {
        return "<init>".equals(methodName) || "<clinit>".equals(methodName);
    }

    private boolean isReturnOrThrow(int opcode) {
        return (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
    }

    private int mixSeed(int a, int b) {
        int x = a ^ Integer.rotateLeft(b, 13) ^ 0x9E3779B9;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        return x == 0 ? 0x13579BDF : x;
    }

    private void pushInt(InsnList list, int value) {
        if (value >= -1 && value <= 5) {
            list.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            list.add(new LdcInsnNode(value));
        }
    }

    private static final class WipingByteArrayInputStream extends java.io.ByteArrayInputStream {
        private boolean wiped;

        private WipingByteArrayInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            wipeAtEnd();
            return value;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, length);
            wipeAtEnd();
            return count;
        }

        @Override
        public synchronized byte[] readAllBytes() {
            byte[] remaining = Arrays.copyOfRange(buf, pos, count);
            pos = count;
            wipe();
            return remaining;
        }

        @Override
        public synchronized byte[] readNBytes(int length) {
            if (length < 0) {
                throw new IllegalArgumentException("length < 0");
            }
            int bytesToRead = Math.min(length, count - pos);
            byte[] result = Arrays.copyOfRange(buf, pos, pos + bytesToRead);
            pos += bytesToRead;
            wipeAtEnd();
            return result;
        }

        @Override
        public synchronized void close() {
            wipe();
        }

        private void wipeAtEnd() {
            if (pos >= count) {
                wipe();
            }
        }

        private void wipe() {
            if (!wiped) {
                Arrays.fill(buf, (byte) 0);
                pos = count;
                wiped = true;
            }
        }
    }
}
