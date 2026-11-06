package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PayloadClassPrivacyTest {
    private static final String NAME = "interstellarTikiTiks/apexx/client/module/implementation/IntegrityPrivacyFixture";
    private byte[] fixture() {
        var w = new ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, NAME, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "integrityBytes", "I", null, null).visitEnd();
        var m = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        m.visitCode();
        m.visitLdcInsn(Type.getObjectType(NAME));
        m.visitLdcInsn("/" + NAME + ".class");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;", false);
        m.visitVarInsn(Opcodes.ASTORE, 0);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "readAllBytes", "()[B", false);
        m.visitInsn(Opcodes.ARRAYLENGTH);
        m.visitFieldInsn(Opcodes.PUTSTATIC, NAME, "integrityBytes", "I");
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(2, 1);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private Map<String, byte[]> retained(ApexxClassLoader loader, String fieldName) throws Exception {
        var field = ApexxClassLoader.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, byte[]>) field.get(loader);
    }

    @Test void encodedBodiesRemainOpaqueUntilDefinitionAndIntegrityResourcesCannotBeExported() throws Exception {
        byte[] plaintext = fixture();
        byte[] encoded = PayloadClassCodec.encode(NAME + ".class", plaintext, CryptoVault.classBodySeed(), CryptoVault.classBodySalt());
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(NAME + ".class"));
            zip.write(encoded);
            zip.closeEntry();
        }
        var loader = new ApexxClassLoader(getClass().getClassLoader());
        loader.loadPlaintextZip(new ByteArrayInputStream(buffer.toByteArray()));
        String binary = NAME.replace('/', '.');
        byte[] retainedBody = retained(loader, "classBytes").get(binary);
        assertFalse(java.util.Arrays.equals(encoded, retainedBody));
        var cipherField = ApexxClassLoader.class.getDeclaredField("classCipher");
        cipherField.setAccessible(true);
        SessionClassCipher cipher = (SessionClassCipher) cipherField.get(loader);
        byte[] opened = cipher.open(binary, retainedBody);
        assertArrayEquals(encoded, opened);
        java.util.Arrays.fill(opened, (byte) 0);
        assertNull(loader.getResourceAsStream(NAME + ".class"));
        Class<?> defined = loader.loadClass(binary);
        assertArrayEquals(new byte[retainedBody.length], retainedBody, "consumed encoded body is wiped too");
        assertTrue(retained(loader, "classBytes").isEmpty());
        byte[] integrityCopy = cipher.open(binary, retained(loader, "classResourceBytes").get(NAME + ".class"));
        assertArrayEquals(encoded, integrityCopy);
        java.util.Arrays.fill(integrityCopy, (byte) 0);
        assertNull(loader.getResourceAsStream(NAME + ".class"), "defining a class does not publish its integrity copy");
        loader.initializePayloadClass(binary);
        assertEquals(plaintext.length, defined.getField("integrityBytes").getInt(null));
        assertTrue(retained(loader, "classResourceBytes").isEmpty());
        assertNull(loader.getResourceAsStream(NAME + ".class"));
        loader.clearSensitiveBytes();
        assertTrue(retained(loader, "classBytes").isEmpty());
    }
    @Test void stampedLoaderRejectsPlainClassBodies() throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(NAME + ".class"));
            zip.write(fixture());
            zip.closeEntry();
        }
        try (var build = org.mockito.Mockito.mockStatic(interstellarTikiTiks.apexx.client.auth.BuildInfo.class)) {
            build.when(interstellarTikiTiks.apexx.client.auth.BuildInfo::isKnown).thenReturn(true);
            var loader = new ApexxClassLoader(getClass().getClassLoader());
            assertThrows(SecurityException.class, () -> loader.loadPlaintextZip(new ByteArrayInputStream(buffer.toByteArray())));
            assertTrue(retained(loader, "classBytes").isEmpty());
        }
    }

}
