package ai.moeru.airicraft;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SingleplayerSaveManagementTest {
	@TempDir Path saves;

	@Test
	void renamePreservesFolderAndOtherSaveData() throws Exception {
		LevelStorageSource storage = fixture();
		SingleplayerWorldService.renameSave(storage, "original", "New title");
		CompoundTag data = NbtIo.readCompressed(saves.resolve("original/level.dat"), NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
		assertEquals("New title", data.getStringOr("LevelName", ""));
		assertEquals(123L, data.getLongOr("Time", 0L));
		assertTrue(Files.exists(saves.resolve("original/region/keep.mca")));
		assertFalse(Files.exists(saves.resolve("New title")));
	}

	@Test
	void deleteRemovesOnlySelectedSave() throws Exception {
		LevelStorageSource storage = fixture();
		Files.createDirectory(saves.resolve("neighbor"));
		SingleplayerWorldService.deleteSave(storage, "original");
		assertFalse(Files.exists(saves.resolve("original")));
		assertTrue(Files.isDirectory(saves.resolve("neighbor")));
	}

	@Test
	void lockedSaveCannotBeRenamedOrDeleted() throws Exception {
		LevelStorageSource storage = fixture();
		try (var session = storage.validateAndCreateAccess("original")) {
			assertEquals("world_locked", assertThrows(SingleplayerWorldService.SingleplayerWorldException.class,
				() -> SingleplayerWorldService.renameSave(storage, "original", "New")).code());
			assertEquals("world_locked", assertThrows(SingleplayerWorldService.SingleplayerWorldException.class,
				() -> SingleplayerWorldService.deleteSave(storage, "original")).code());
			assertTrue(Files.exists(saves.resolve("original/level.dat")));
		}
	}

	@Test
	void missingSaveIsNotCreatedByMutation() throws Exception {
		LevelStorageSource storage = fixture();
		assertEquals("world_not_found", assertThrows(SingleplayerWorldService.SingleplayerWorldException.class,
			() -> SingleplayerWorldService.deleteSave(storage, "missing")).code());
		assertFalse(Files.exists(saves.resolve("missing")));
	}

	@Test
	void refusesSymlinkedSaveWithoutTouchingTarget() throws Exception {
		LevelStorageSource storage = fixture();
		Files.createSymbolicLink(saves.resolve("alias"), saves.resolve("original"));
		assertEquals("invalid_request", assertThrows(SingleplayerWorldService.SingleplayerWorldException.class,
			() -> SingleplayerWorldService.deleteSave(storage, "alias")).code());
		assertTrue(Files.exists(saves.resolve("original/level.dat")));
	}

	@Test
	void rejectsBlankRenameWithoutChangingMetadata() throws Exception {
		LevelStorageSource storage = fixture();
		byte[] before = Files.readAllBytes(saves.resolve("original/level.dat"));
		assertEquals("invalid_request", assertThrows(SingleplayerWorldService.SingleplayerWorldException.class,
			() -> SingleplayerWorldService.renameSave(storage, "original", " ")).code());
		assertArrayEquals(before, Files.readAllBytes(saves.resolve("original/level.dat")));
	}

	private LevelStorageSource fixture() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		Files.createDirectories(saves.resolve("original/region"));
		Files.writeString(saves.resolve("original/region/keep.mca"), "chunk data");
		CompoundTag data = new CompoundTag();
		data.putString("LevelName", "Original");
		data.putLong("Time", 123L);
		CompoundTag root = new CompoundTag();
		root.put("Data", data);
		NbtIo.writeCompressed(root, saves.resolve("original/level.dat"));
		return LevelStorageSource.createDefault(saves);
	}
}
