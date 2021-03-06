package com.divinitor.dn.lib.game.mod.compiler;

import co.phoenixlab.dn.util.DnStringUtils;
import co.phoenixlab.dn.util.LittleEndianDataOutputStream;
import co.phoenixlab.dn.util.Sparser;
import com.divinitor.dn.lib.game.mod.CompileException;
import com.divinitor.dn.lib.game.mod.DnAssetAccessService;
import com.divinitor.dn.lib.game.mod.ModKit;
import com.divinitor.dn.lib.game.mod.UnsupportedVersionException;
import com.divinitor.dn.lib.game.mod.compiler.processors.Processors;
import com.divinitor.dn.lib.game.mod.definition.*;
import com.divinitor.dn.lib.game.mod.pak.ManagedPak;
import com.divinitor.dn.lib.game.mod.pak.ManagedPakIndexEntry;
import com.divinitor.dn.lib.game.mod.pak.ManagedPakModIndexEntry;
import com.divinitor.dn.lib.game.mod.util.Utils;
import com.google.common.base.Strings;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import net.openhft.hashing.LongHashFunction;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static com.divinitor.dn.lib.game.mod.compiler.ModCompiler.gameSource;
import static com.divinitor.dn.lib.game.mod.compiler.ModCompiler.packSource;
import static com.divinitor.dn.lib.game.mod.pak.ManagedPakModIndexEntry.*;
import static java.nio.file.StandardOpenOption.*;

public class SingleModCompiler implements ModCompiler {


    private final ModKit kit;
    private final DnAssetAccessService assetAccessService;
    private ModPackage modPack;
    private Path target;
    private BuildComputeResults buildComputeResults;
    private TableEditor tableEditor;

    public SingleModCompiler(ModKit kit) {
        this.kit = kit;
        this.assetAccessService = kit.getAssetAccessService();
        this.tableEditor = new TableEditor(this.assetAccessService);
    }

    private void checkKitCompilerVersion(ModPackage modPackage) throws UnsupportedVersionException {
        BuildInfo build = modPackage.getBuild();
        if (build.getKitVersion().compareTo(ModKit.KIT_VERSION) > 0) {
            throw new UnsupportedVersionException(ModKit.KIT_VERSION, build.getKitVersion());
        }
    }

    @Override
    public BuildComputeResults compute() {
        if (this.modPack == null) {
            throw new IllegalStateException("ModPackage not specified");
        }

        this.checkKitCompilerVersion(this.modPack);

        if (this.buildComputeResults != null) {
            return this.buildComputeResults;
        }

        BuildComputeResults results = new BuildComputeResults();
        results.missing = MultimapBuilder.hashKeys().hashSetValues().build();
        results.conflicts = new ArrayList<>();
        results.rejected = MultimapBuilder.hashKeys().hashSetValues().build();
        results.steps = new ArrayList<>();

        SetMultimap<String, String> destinationFiles = MultimapBuilder.hashKeys().linkedHashSetValues().build();
        List<FileBuildStep> steps = results.steps;

        BuildInfo build = this.modPack.getBuild();

        if (build.getCopy() != null) {
            for (CopyFromGameDirective directive : build.getCopy()) {
                String src = directive.getSource();
                if (!this.assetAccessService.contains(src)) {
                    results.missing.put(this.modPack.getId(), "pak::" + src);
                    continue;
                }

                String dest = directive.getDest();
                destinationFiles.put(dest, this.modPack.getId());

                steps.add(new FileBuildStep(this.modPack, dest, gameSource(this.assetAccessService, src),
                    directive.getCompressionLevel()));
            }
        }

        if (build.getAdd() != null) {
            for (CopyFromPackDirective directive : build.getAdd()) {
                String src = directive.getSource();
                if (!this.modPack.hasAsset(src)) {
                    results.missing.put(this.modPack.getId(), "mod::" + src);
                    continue;
                }

                String dest = directive.getDest();
                destinationFiles.put(dest, this.modPack.getId());
                Utils.ThrowingSupplier<byte[]> source;
                if (Strings.isNullOrEmpty(directive.getProcessor())) {
                    source = packSource(this.modPack, src);
                } else {
                    source = Processors.getProcessor(directive.getProcessor()).process(this.modPack, src);
                }

                steps.add(new FileBuildStep(this.modPack, dest, source, directive.getCompressionLevel()));
            }
        }

        if (build.getEditTable() != null) {
            for (TableEditDirective directive : build.getEditTable()) {
                String tableName = directive.getTableName();
                if (!tableName.endsWith(".dnt")) {
                    tableName = tableName + ".dnt";
                }

                if (!this.assetAccessService.contains(tableName)) {
                    results.missing.put(this.modPack.getId(), "pak::" + tableName);
                    continue;
                }

                String dest;
                try {
                    dest = this.assetAccessService.resolve(tableName);
                } catch (FileNotFoundException fnfe) {
                    results.missing.put(this.modPack.getId(), "pak::" + tableName);
                    continue;
                }

                steps.add(new FileBuildStep(this.modPack, dest, tableEditor.tableEdit(tableName, directive),
                    directive.getCompressionLevel()));
            }
        }

        destinationFiles.asMap().entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .forEach((e) -> results.conflicts.add(BuildConflict.builder()
                .file(e.getKey())
                .conflictingModIds(new LinkedHashSet<>(e.getValue()))
                .build()));

        this.buildComputeResults = results;

        return results;
    }

    @Override
    public void compile() throws CompileException {
        BuildComputeResults results = this.buildComputeResults;
        if (results == null) {
            results = this.compute();
        }

        List<FileBuildStep> steps = results.steps;

        ManagedPak mPak = new ManagedPak();

        mPak.setMagicNumber(ManagedPak.MAGIC_NUMBER);
        mPak.setVersion(ManagedPak.CURRENT_VERSION);
        mPak.setFileCount(steps.size());
        mPak.setModPackCount(1);
        mPak.setManagedMajorVersion(ManagedPak.CURRENT_MANAGED_VERSION.getMajorVersion());
        mPak.setManagedMinorVersion(ManagedPak.CURRENT_MANAGED_VERSION.getMinorVersion());

        mPak.setModIndex(new ManagedPakModIndexEntry[]{builder()
            .id(this.modPack.getId())
            .name(this.modPack.getName())
            .version(this.modPack.getVersion())
            .build()});

        mPak.setFileIndex(new ManagedPakIndexEntry[mPak.getFileCount()]);

        LongHashFunction xx = LongHashFunction.xx();
        ManagedPakIndexEntry[] fileIndex = mPak.getFileIndex();

        long end;

        try (FileChannel channel = FileChannel.open(this.target, WRITE, CREATE, TRUNCATE_EXISTING, SPARSE)) {
            //  We'll write the header later
            channel.position(ManagedPak.SIZEOF_HEADER);
            int i = 0;
            for (FileBuildStep step : steps) {
                try {
                    long start = channel.position();
                    byte[] data = step.getSource().get();

                    long hash = xx.hashBytes(data);

                    OutputStream out = Channels.newOutputStream(channel);   //  DO NOT CLOSE THIS STREAM
                    DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(
                        out,
                        new Deflater(Optional.ofNullable(step.getCompressionLevel()).orElse(Deflater.BEST_COMPRESSION)));
                    deflaterOutputStream.write(data);
                    deflaterOutputStream.finish();
                    deflaterOutputStream.flush();

                    int compressedSize = (int) (channel.position() - start);
                    ManagedPakIndexEntry entry = ManagedPakIndexEntry.builder()
                        .filePath(step.getDestination())
                        .offset((int) start)
                        .compressedSize(compressedSize)
                        .rawSize(compressedSize)
                        .realSize(data.length)
                        .unknownA(0)
                        .contentHash(hash)
                        .remainder(ManagedPakIndexEntry.REMAINDER_INSTANCE)
                        .build();
                    fileIndex[i] = entry;
                } catch (IOException e) {
                    throw new IOException("IO exception for asset " + step.getDestination(), e);
                } catch (Exception e) {
                    throw new CompileException("Unable to package asset " + step.getDestination(), e);
                } finally {
                    ++i;
                }
            }

            //  Write mod index
            mPak.setModPackIndexTableOffset((int) channel.position());
            LittleEndianDataOutputStream out = new LittleEndianDataOutputStream(Channels.newOutputStream(channel));
            for (ManagedPakModIndexEntry entry : mPak.getModIndex()) {
                DnStringUtils.writeFixedBufferString(entry.getId(), SIZEOF_ID, out);
                DnStringUtils.writeFixedBufferString(entry.getName(), SIZEOF_NAME, out);
                DnStringUtils.writeFixedBufferString(entry.getVersion().toString(), SIZEOF_VERSION, out);
            }

            //  Write pak index
            mPak.setFileIndexTableOffset((int) channel.position());
            for (ManagedPakIndexEntry entry : fileIndex) {
                DnStringUtils.writeFixedBufferString(entry.getFilePath().replace('/', '\\'),
                    ManagedPakIndexEntry.SIZEOF_FILE_PATH, out);
                out.writeInt(entry.getRawSize());
                out.writeInt(entry.getRealSize());
                out.writeInt(entry.getCompressedSize());
                out.writeInt(entry.getOffset());
                out.writeInt(entry.getUnknownA());
                out.writeLong(entry.getContentHash());
                out.write(entry.getRemainder());
            }

            end = channel.position();

            if (channel.position() <= ManagedPak.HALF_GIGABYTE) {
                channel.position(ManagedPak.HALF_GIGABYTE);
                channel.write(ByteBuffer.wrap(new byte[1]));
            }

            channel.position(0);
            out = new LittleEndianDataOutputStream(Channels.newOutputStream(channel));
            DnStringUtils.writeFixedBufferString(mPak.getMagicNumber(), ManagedPak.SIZEOF_MAGIC_NUMBER, out);
            out.writeShort(mPak.getManagedMajorVersion());
            out.writeShort(mPak.getManagedMinorVersion());
            out.writeInt(mPak.getModPackCount());
            out.writeInt(mPak.getModPackIndexTableOffset());

            byte[] headerBuffer = new byte[ManagedPak.SIZEOF_BUFFER];
            out.write(headerBuffer);

            out.writeInt(mPak.getVersion());
            out.writeInt(mPak.getFileCount());
            out.writeInt(mPak.getFileIndexTableOffset());
        } catch (IOException e) {
            throw new CompileException("Failed to write output", e);
        }

        try {
            Sparser.markSparse(this.target);
            Sparser.markSparseRange(this.target, end, ManagedPak.HALF_GIGABYTE - end);
        } catch (IOException e) {
            //  Don't care
        }
    }

    public ModPackage getModPack() {
        return modPack;
    }

    public void setModPack(ModPackage modPack) {
        this.modPack = modPack;
        this.buildComputeResults = null;
    }

    public Path getTarget() {
        return target;
    }

    public void setTarget(Path target) {
        this.target = target;
        this.buildComputeResults = null;
    }


}
