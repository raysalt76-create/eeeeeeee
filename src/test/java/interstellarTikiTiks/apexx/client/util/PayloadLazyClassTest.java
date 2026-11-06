package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PayloadLazyClassTest {
    private static class InspectableLoader extends ApexxClassLoader {
        InspectableLoader() { super(PayloadLazyClassTest.class.getClassLoader()); }
        boolean defined(String name) { return findLoadedClass(name) != null; }
    }

    @Test void catalogReadsHeadersWithoutDefiningModulesOrTheirHelpers() throws Exception {
        String root = "interstellarTikiTiks/apexx/client/";
        Map<String, String> fixture = new LinkedHashMap<>();
        fixture.put("fixture/Helper", "java/lang/Object");
        fixture.put("fixture/Base", root+"module/Module");
        fixture.put("fixture/Feature", "fixture/Base");
        Map<String, byte[]> plaintext = new HashMap<>();
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : fixture.entrySet()) {
                var writer = new ClassWriter(0);
                writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | (entry.getKey().endsWith("Base") ? Opcodes.ACC_ABSTRACT : 0),
                        entry.getKey(), null, entry.getValue(), null);
                writer.visitEnd();
                byte[] body = writer.toByteArray();
                plaintext.put(entry.getKey().replace('/', '.'), body);
                zip.putNextEntry(new ZipEntry(entry.getKey()+".class")); zip.write(body); zip.closeEntry();
            }
        }
        var loader = new InspectableLoader();
        loader.loadPlaintextZip(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(Set.of("fixture.Feature"), loader.getModuleClassNames());
        for (String name : fixture.keySet()) assertFalse(loader.defined(name.replace('/', '.')));
        assertNull(loader.getResourceAsStream("fixture/Feature.class"), "outside callers cannot export class bodies");
        var field = ApexxClassLoader.class.getDeclaredField("classBytes"); field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, byte[]> retained = (Map<String, byte[]>) field.get(loader);
        List<byte[]> references = new ArrayList<>(retained.values());
        for (var entry : retained.entrySet()) assertFalse(Arrays.equals(plaintext.get(entry.getKey()), entry.getValue()));
        loader.clearSensitiveBytes();
        for (byte[] sealed : references) assertArrayEquals(new byte[sealed.length], sealed);
        assertTrue(loader.getModuleClassNames().isEmpty());
    }
}
