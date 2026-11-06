package interstellarTikiTiks.apexx.client.resource;

import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.Resource;
import net.minecraft.resource.metadata.ResourceMetadata;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public final class RuntimeMemoryResource {
    private RuntimeMemoryResource() {
    }

    public static Resource create(byte[] data) {
        if (data == null) {
            return null;
        }

        InputSupplier<InputStream> supplier = () -> new ByteArrayInputStream(data);
        return new Resource(null, supplier, () -> ResourceMetadata.NONE);
    }
}
