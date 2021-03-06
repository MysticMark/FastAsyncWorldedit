package com.boydti.fawe.jnbt.anvil;

import com.boydti.fawe.example.CharFaweChunk;
import com.boydti.fawe.example.NMSMappedFaweQueue;
import com.boydti.fawe.object.FaweChunk;
import com.boydti.fawe.object.FawePlayer;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.RunnableVal4;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.world.biome.BaseBiome;
import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

public class MCAQueue extends NMSMappedFaweQueue<FaweQueue, FaweChunk, FaweChunk, FaweChunk> {

    private FaweQueue parent;
    private NMSMappedFaweQueue parentNMS;
    private final boolean hasSky;
    private final File saveFolder;

    public MCAQueue(FaweQueue parent) {
        super(parent.getWEWorld(), new MCAQueueMap());
        this.parent = parent;
        if (parent instanceof NMSMappedFaweQueue) {
            parentNMS = (NMSMappedFaweQueue) parent;
        }
        ((MCAQueueMap) getFaweQueueMap()).setParentQueue(parent);
        hasSky = parent.hasSky();
        saveFolder = parent.getSaveFolder();
    }

    public MCAQueue(String world, File saveFolder, boolean hasSky) {
        super(world, new MCAQueueMap());
        ((MCAQueueMap) getFaweQueueMap()).setParentQueue(this);
        this.saveFolder = saveFolder;
        this.hasSky = hasSky;
    }

    @Override
    public FaweChunk loadChunk(FaweQueue faweQueue, int x, int z, boolean generate) {
        return getFaweChunk(x, z);
    }

    @Override
    public FaweChunk getSections(FaweChunk faweChunk) {
        return faweChunk;
    }

    @Override
    public FaweChunk getCachedChunk(FaweQueue faweQueue, int cx, int cz) {
        return getFaweChunk(cx, cz);
    }

    @Override
    public int getBiome(FaweChunk faweChunk, int x, int z) {
        if (faweChunk instanceof MCAChunk) {
            return ((MCAChunk) faweChunk).getBiomeArray()[((z & 0xF) << 4 | x & 0xF)];
        } else if (parent != null){
            return parent.getBiomeId(x, z);
        } else {
            return 0;
        }
    }

    public void filterWorld(final MCAFilter filter) {
        File folder = getSaveFolder();
        final ForkJoinPool pool = new ForkJoinPool();
        final ThreadLocal<MutableMCABackedBaseBlock> blockStore = new ThreadLocal<MutableMCABackedBaseBlock>() {
            @Override
            protected MutableMCABackedBaseBlock initialValue() {
                return new MutableMCABackedBaseBlock();
            }
        };
        for (final File file : folder.listFiles()) {
            try {
                String name = file.getName();
                String[] split = name.split("\\.");
                final int mcaX = Integer.parseInt(split[1]);
                final int mcaZ = Integer.parseInt(split[2]);
                if (filter.appliesFile(mcaX, mcaZ)) {
                    MCAFile mcaFile = new MCAFile(this, file);
                    final MCAFile original = mcaFile;
                    final MCAFile finalFile = filter.applyFile(mcaFile);
                    if (finalFile != null) {
                        finalFile.init();
                        // May not do anything, but seems to lead to smaller lag spikes
                        final int cbx = mcaX << 5;
                        final int cbz = mcaZ << 5;

                        finalFile.forEachSortedChunk(new RunnableVal4<Integer, Integer, Integer, Integer>() {
                            @Override
                            public void run(final Integer rcx, final Integer rcz, Integer offset, Integer size) {
                                pool.submit(new Runnable() {
                                    @Override
                                    public void run() {
                                        int cx = cbx + rcx;
                                        int cz = cbz + rcz;
                                        if (filter.appliesChunk(cx, cz)) {
                                            try {
                                                MCAChunk chunk = finalFile.getChunk(cx, cz);
                                                try {
                                                    chunk = filter.applyChunk(chunk);
                                                    if (chunk != null) {
                                                        final MutableMCABackedBaseBlock mutableBlock = blockStore.get();
                                                        mutableBlock.setChunk(chunk);
                                                        int bx = cx << 4;
                                                        int bz = cz << 4;
                                                        for (int layer = 0; layer < chunk.ids.length; layer++) {
                                                            if (chunk.doesSectionExist(layer)) {
                                                                mutableBlock.setArrays(layer);
                                                                int yStart = layer << 4;
                                                                int index = 0;
                                                                for (int y = yStart; y < yStart + 16; y++) {
                                                                    mutableBlock.setY(y);
                                                                    for (int z = bz; z < bz + 16; z++) {
                                                                        mutableBlock.setZ(z);
                                                                        for (int x = bx; x < bx + 16; x++,index++) {
                                                                            mutableBlock.setX(x);
                                                                            mutableBlock.setIndex(index);
                                                                            filter.applyBlock(x, y, z, mutableBlock);
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (Throwable e) {
                                                    e.printStackTrace();
                                                }
                                            } catch (Throwable e) {
                                                System.out.println("Failed to load: r." + mcaX + "." + mcaZ + ".mca -> (local) " + rcx + "," + rcz);
                                                e.printStackTrace();
                                            }
                                        }
                                    }
                                });
                            }
                        });
                        pool.awaitQuiescence(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                        original.close(pool);
                        if (original != finalFile) finalFile.close(pool);
                    } else {
                        try {
                            original.close(pool);
                            file.delete();
                        } catch (Throwable ignore) {}
                    }
                }
            } catch (Throwable ignore) {
                ignore.printStackTrace();
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void relight(int x, int y, int z) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void relightBlock(int x, int y, int z) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void relightSky(int x, int y, int z) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public boolean regenerateChunk(FaweQueue faweQueue, int x, int z, BaseBiome biome, Long seed) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public CharFaweChunk getPrevious(CharFaweChunk fs, FaweChunk sections, Map<?, ?> tiles, Collection<?>[] entities, Set<UUID> createdEntities, boolean all) throws Exception {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public FaweQueue getImpWorld() {
        return parent;
    }

    @Override
    public void setHeightMap(FaweChunk chunk, byte[] heightMap) {
        MCAChunk mca = (MCAChunk) chunk;
        if (mca != null) {
            int[] otherMap = mca.getHeightMapArray();
            for (int i = 0; i < heightMap.length; i++) {
                int newHeight = heightMap[i] & 0xFF;
                int currentHeight = otherMap[i];
                if (newHeight > currentHeight) {
                    otherMap[i] = newHeight;
                }
            }
        }
    }

    @Override
    public void setFullbright(FaweChunk sections) {
        if (sections.getClass() == MCAChunk.class) {
            ((MCAChunk) sections).setFullbright();
        } else if (parentNMS != null) {
            int cx = sections.getX();
            int cz = sections.getZ();
            parentNMS.ensureChunkLoaded(cx, cz);
            Object parentSections = parentNMS.getCachedSections(parentNMS.getWorld(), cx, cz);
            if (parentSections != null) {
                parentNMS.setFullbright(sections);
            }
        }
    }

    @Override
    public boolean removeLighting(FaweChunk sections, RelightMode mode, boolean hasSky) {
        if (mode != RelightMode.NONE) {
            if (sections.getClass() == MCAChunk.class) {
                ((MCAChunk) sections).removeLight();
            } else if (parentNMS != null) {
                int cx = sections.getX();
                int cz = sections.getZ();
                parentNMS.ensureChunkLoaded(cx, cz);
                Object parentSections = parentNMS.getCachedSections(parentNMS.getWorld(), cx, cz);
                if (parentSections != null) {
                    parentNMS.removeLighting(sections, mode, hasSky);
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void setSkyLight(FaweChunk sections, int x, int y, int z, int value) {
        if (sections.getClass() == MCAChunk.class) {
            ((MCAChunk) sections).setSkyLight(x, y, z, value);
        } else if (parentNMS != null) {
            parentNMS.setSkyLight(x, y, z, value);
        }
    }

    @Override
    public void setBlockLight(FaweChunk sections, int x, int y, int z, int value) {
        if (sections.getClass() == MCAChunk.class) {
            ((MCAChunk) sections).setBlockLight(x, y, z, value);
        } else if (parentNMS != null) {
            parentNMS.setBlockLight(x, y, z, value);
        }
    }

    @Override
    public void refreshChunk(FaweChunk fs) {
        if (fs.getClass() != MCAChunk.class) {
            parentNMS.sendChunk(fs);
        }
    }

    @Override
    public void sendChunk(int x, int z, int bitMask) {
        if (parentNMS != null) {
            parentNMS.sendChunk(x, z, bitMask);
        }
    }

    @Override
    public CompoundTag getTileEntity(FaweChunk sections, int x, int y, int z) {
        if (sections.getClass() == MCAChunk.class) {
            return sections.getTile(x, y, z);
        } else {
            return parentNMS.getTileEntity(x, y, z);
        }
    }

    @Override
    public FaweChunk getFaweChunk(int cx, int cz) {
        return getFaweQueueMap().getFaweChunk(cx, cz);
    }

    @Override
    public File getSaveFolder() {
        return saveFolder;
    }

    @Override
    public boolean hasSky() {
        return hasSky;
    }

    @Override
    public MCAChunk getCachedSections(FaweQueue faweQueue, int cx, int cz) {
        return (MCAChunk) getFaweQueueMap().getFaweChunk(cx, cz);
    }

    @Override
    public FaweChunk getCachedSection(FaweChunk sections, int cy) {
        if (sections.getClass() == MCAChunk.class) {
            if (((MCAChunk) sections).doesSectionExist(cy)) {
                return sections;
            }
            return null;
        } else if (parentNMS != null) {
            return sections;
        }
        return null;
    }

    @Override
    public int getCombinedId4Data(FaweChunk sections, int x, int y, int z) {
        if (sections.getClass() == MCAChunk.class) {
            return sections.getBlockCombinedId(x, y, z);
        } else {
            return parentNMS.getCombinedId4Data(x, y, z);
        }
    }

    @Override
    public int getSkyLight(FaweChunk sections, int x, int y, int z) {
        if (sections.getClass() == MCAChunk.class) {
            return ((MCAChunk) sections).getSkyLight(x, y, z);
        } else {
            return parentNMS.getSkyLight(x, y, z);
        }
    }

    @Override
    public int getEmmittedLight(FaweChunk sections, int x, int y, int z) {
        if (sections.getClass() == MCAChunk.class) {
            return ((MCAChunk) sections).getBlockLight(x, y, z);
        } else {
            return parentNMS.getEmmittedLight(x, y, z);
        }
    }

    @Override
    public void startSet(boolean parallel) {
        if (parent != null) {
            parent.startSet(parallel);
        }
    }

    @Override
    public void endSet(boolean parallel) {
        if (parent != null) {
            parent.endSet(parallel);
        }
    }

    @Override
    public void sendBlockUpdate(FaweChunk chunk, FawePlayer... players) {
        if (parent != null) {
            parentNMS.sendBlockUpdate(chunk, players);
        }
    }
}
