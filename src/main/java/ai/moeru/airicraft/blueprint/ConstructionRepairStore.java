package ai.moeru.airicraft.blueprint;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small atomic journal. Call on the storage executor, never while ticking the world. */
public final class ConstructionRepairStore {
    private static final int MAX_BYTES=2*1024*1024;
    private final Path path;
    public ConstructionRepairStore(Path path){this.path=Objects.requireNonNull(path);}
    public Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> load() throws IOException {
        if(!Files.exists(path))return Map.of();
        if(Files.size(path)>MAX_BYTES)throw new IOException("construction_journal_too_large");
        try {
            var root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(root.get("version").getAsInt()!=1)throw new IllegalArgumentException("unsupported_version");
            var entries=root.getAsJsonArray("debts");if(entries.size()>8192)throw new IllegalArgumentException("too_many_debts");
            var result=new LinkedHashMap<ConstructionEscape.Position,ConstructionRepairLedger.Debt>();
            for(var value:entries){
                var e=value.getAsJsonObject();var p=new ConstructionEscape.Position(e.get("x").getAsInt(),e.get("y").getAsInt(),e.get("z").getAsInt());
                var debt=new ConstructionRepairLedger.Debt(e.get("requiredState").getAsString(),e.get("component").getAsString(),e.get("intermediateState").getAsString());
                if(result.put(p,debt)!=null)throw new IllegalArgumentException("duplicate_position");
            }
            return Map.copyOf(result);
        }catch(RuntimeException e){throw new IOException("invalid_construction_journal: "+path,e);}
    }
    public void save(Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts) throws IOException {
        if(debts.size()>8192)throw new IOException("too_many_construction_debts");
        var root=new JsonObject();root.addProperty("version",1);var entries=new JsonArray();
        debts.forEach((p,d)->{var e=new JsonObject();e.addProperty("x",p.x());e.addProperty("y",p.y());e.addProperty("z",p.z());e.addProperty("requiredState",d.requiredState());e.addProperty("component",d.component());e.addProperty("intermediateState",d.intermediateState());entries.add(e);});root.add("debts",entries);
        byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("construction_journal_too_large");
        Files.createDirectories(path.getParent());var temp=Files.createTempFile(path.getParent(),"construction-",".tmp");
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){var data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);}
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
}
