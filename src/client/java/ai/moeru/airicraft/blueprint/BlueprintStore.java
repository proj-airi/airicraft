package ai.moeru.airicraft.blueprint;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Versioned, atomic world-local semantic data. Never executes persisted source. */
final class BlueprintStore {
    private final Path file;
    BlueprintStore(Path file){this.file=file;}
    JsonObject load() throws IOException {
        if(!Files.exists(file))return new JsonObject();
        if(Files.size(file)>64L*1024*1024)throw new IOException("blueprint_store_too_large");
        try {
            var value=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(!value.has("version")||value.get("version").getAsInt()!=1)throw new IOException("unsupported_blueprint_store_version");
            return value;
        }catch(RuntimeException e){throw new IOException("invalid_blueprint_store",e);}
    }
    void save(JsonObject value) throws IOException {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>64L*1024*1024)throw new IOException("blueprint_store_too_large");
        Files.createDirectories(file.getParent());
        Path temporary=Files.createTempFile(file.getParent(),"blueprints-",".tmp");
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
}
