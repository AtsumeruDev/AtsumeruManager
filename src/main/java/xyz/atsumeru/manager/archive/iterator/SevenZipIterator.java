package xyz.atsumeru.manager.archive.iterator;

import javafx.beans.property.BooleanProperty;
import lombok.Getter;
import net.sf.sevenzipjbinding.*;
import net.sf.sevenzipjbinding.impl.OutItemFactory;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import net.sf.sevenzipjbinding.util.ByteArrayStream;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import xyz.atsumeru.manager.utils.globalutils.GUFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SevenZipIterator implements IArchiveIterator {
    private static final String TEMP_ARCHIVE_EXTENSION = ".sevenzip_tmp";

    @Getter
    private String archivePath;
    private RandomAccessFile randomAccessFile;
    private IInArchive inArchive;

    private ListIterator<ISimpleInArchiveItem> iterator;
    private ISimpleInArchiveItem entry;

    public static SevenZipIterator create() {
        return new SevenZipIterator();
    }

    public static boolean unpack(File inputFile, String outputDir, @Nullable Consumer<String> fileNameConsumer,
                                 @Nullable BiConsumer<Integer, Integer> progressConsumer, @Nullable BooleanProperty interrupted) {
        return unpack(inputFile.getAbsolutePath(), outputDir, fileNameConsumer, progressConsumer, interrupted);
    }

    public static boolean unpack(String inputFilePath, String outputDir, @Nullable Consumer<String> fileNameConsumer,
                                 @Nullable BiConsumer<Integer, Integer> progressConsumer, @Nullable BooleanProperty interrupted) {
        SevenZipIterator iterator = SevenZipIterator.create();
        try {
            iterator.open(inputFilePath);
            int countFiles = iterator.countFiles();

            int currentFile = 1;
            while (iterator.next()) {
                if (interrupted != null && interrupted.getValue()) {
                    return false;
                }

                String entryPath = iterator.getEntryPath();
                InputStream in = iterator.getEntryInputStream();

                if (fileNameConsumer != null) {
                    fileNameConsumer.accept(entryPath);
                }

                String entryPathWithoutFile = GUFile.getPath(entryPath);
                File outFile = new File(outputDir + entryPathWithoutFile);
                outFile.mkdirs();

                OutputStream out = new FileOutputStream(new File(outFile, GUFile.getFileNameWithExt(entryPath, true)));
                IOUtils.copy(in, out);

                if (progressConsumer != null) {
                    progressConsumer.accept(currentFile, countFiles);
                }

                GUFile.closeQuietly(in);
                GUFile.closeQuietly(out);

                currentFile++;
            }

            return true;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.err.println("Unable to create temp dir");
            return false;
        } catch (SevenZipException e) {
            e.printStackTrace();
            System.err.println("Unable to read archive stream");
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Unable to open archive or unpack file");
            return false;
        } finally {
            GUFile.closeQuietly(iterator);
        }
    }

    @Override
    public List<String> getMediaTypes() {
        return Arrays.asList("application/x-rar-compressed", "application/x-rar-compressed; version=4", "application/x-7z-compressed");
    }

    @Override
    public IArchiveIterator createInstance() {
        return create();
    }

    @Override
    public void open(String archivePath) throws IOException {
        this.archivePath = archivePath;

        System.out.println("[INFO] [SevenZipIterator] Reading archive: " + archivePath);
        // Открытие архива для чтения

        randomAccessFile = new RandomAccessFile(archivePath, "r");
        inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(randomAccessFile));

        reset();
    }

    @Override
    public void reset() throws IOException {
        iterator = Arrays.stream(inArchive.getSimpleInterface().getArchiveItems())
                .filter(it -> {
                    try {
                        return !it.isFolder();
                    } catch (SevenZipException e) {
                        return false;
                    }
                })
                .sorted((entry1, entry2) -> {
                    try {
                        return natSortComparator.compare(entry1.getPath().toLowerCase(), entry2.getPath().toLowerCase());
                    } catch (SevenZipException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toList())
                .listIterator();
    }

    @Override
    public boolean next() {
        if (iterator.hasNext()) {
            entry = iterator.next();
            return true;
        }
        return false;
    }

    @Override
    public long getEntrySize() throws IOException {
        return entry.getSize();
    }

    @Override
    public String getEntryName() {
        try {
            return entry.getPath();
        } catch (SevenZipException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getEntryPath() {
        try {
            return entry.getPath();
        } catch (SevenZipException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public InputStream getEntryInputStream() throws SevenZipException {
        List<ByteArrayInputStream> arrayInputStreams = new ArrayList<>();
        entry.extractSlow(new SevenZipOutputStream(arrayInputStreams));
        return new SequenceInputStream(Collections.enumeration(arrayInputStreams));
    }

    @Override
    public InputStream getEntryInputStreamByName(String entryName) throws SevenZipException {
        while (next()) {
            if (getEntryName().equals(entryName)) {
                return getEntryInputStream();
            }
        }
        return null;
    }

    @Override
    public boolean saveIntoArchive(String filePath, String fileName, String fileContent) {
        String outPath = filePath + TEMP_ARCHIVE_EXTENSION;
        boolean success = new AddOrUpdateItem().compress(inArchive, outPath, fileName, fileContent);
        close();

        if (success) {
            try {
                Files.move(new File(outPath).toPath(), new File(filePath).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
                System.err.println("[CRIT] Unable to move modified archive (" + outPath + ")");
                return false;
            }
        }
        return success;
    }

    @Override
    public boolean saveIntoArchive(String filePath, Map<String, String> fileNameWithContentMap) {
        AtomicBoolean success = new AtomicBoolean(true);
        fileNameWithContentMap.forEach((fileName, fileContent) -> success.set(success.get() && saveIntoArchive(filePath, fileName, fileContent)));
        return success.get();
    }

    public int countFiles() throws SevenZipException {
        return inArchive.getNumberOfItems();
    }

    @Override
    public void close() {
        iterator = null;
        entry = null;
        GUFile.closeQuietly(randomAccessFile);
        GUFile.closeQuietly(inArchive);
    }

    private enum WriteMode { ADD, UPDATE }

    private static class AddOrUpdateItem {
        WriteMode writeMode;
        int itemToAddOrUpdateIndex;
        String itemToAddOrUpdatePath;
        byte[] itemToAddOrUpdateContent;

        private void initUpdate(IInArchive inArchive, String fileToAddOrUpdateName, String fileToAddOrUpdateContent) throws SevenZipException {
            itemToAddOrUpdatePath = fileToAddOrUpdateName;
            itemToAddOrUpdateContent = fileToAddOrUpdateContent.getBytes();

            for (int i = 0; i < inArchive.getNumberOfItems(); i++) {
                if (inArchive.getProperty(i, PropID.PATH).equals(fileToAddOrUpdateName)) {
                    itemToAddOrUpdateIndex = i;
                    writeMode = WriteMode.UPDATE;
                    break;
                }
            }

            if (writeMode != WriteMode.UPDATE) {
                System.err.println("Item '" + itemToAddOrUpdatePath + "' not found. Inserting new one...");
                writeMode = WriteMode.ADD;
                itemToAddOrUpdateIndex = inArchive.getNumberOfItems() - 1;
            }
        }

        protected boolean compress(IInArchive inArchive, String fileOutputPath, String fileToAddOrUpdateName, String fileToAddOrUpdateContent) {
            boolean success = false;
            boolean unableToWrite = false;

            RandomAccessFile outRaf;
            IOutUpdateArchive<IOutItemAllFormats> outArchive;
            List<Closeable> closeables = new ArrayList<>();

            try {
                initUpdate(inArchive, fileToAddOrUpdateName, fileToAddOrUpdateContent);

                outRaf = new RandomAccessFile(fileOutputPath, "rw");
                closeables.add(outRaf);

                // Open out-archive object
                outArchive = inArchive.getConnectedOutArchive();

                // Modify archive
                outArchive.updateItems(new RandomAccessFileOutStream(outRaf), inArchive.getNumberOfItems(), new MyCreateCallback());
                success = true;
            } catch (SevenZipException e) {
                System.err.println("7z-Error occurs:");
                // Get more information using extended method
                e.printStackTraceExtended();
            } catch (Exception e) {
                System.err.println("Error occurs: " + e);
                unableToWrite = true;
            } finally {
                for (int i = closeables.size() - 1; i >= 0; i--) {
                    try {
                        GUFile.closeQuietly(closeables.get(i));
                    } catch (Throwable e) {
                        System.err.println("Error closing resource: " + e);
                        success = false;
                    }
                }

                if (unableToWrite) {
                    new File(fileOutputPath).delete();
                }
            }

            if (success) {
                System.out.println("[SUCC] [SevenZipIterator] Add or Update successful");
            } else {
                System.err.println("[CRIT] [SevenZipIterator] Unable to Add or Update archive");
            }

            return success;
        }

        /**
         * The callback defines the modification to be made.
         */
        private final class MyCreateCallback implements IOutCreateCallback<IOutItemAllFormats> {

            public void setOperationResult(boolean operationResultOk) {
                // Track each operation result here
            }

            public void setTotal(long total) {
                // Track operation progress here
            }

            public void setCompleted(long complete) {
                // Track operation progress here
            }

            public IOutItemAllFormats getItemInformation(int index, OutItemFactory<IOutItemAllFormats> outItemFactory) throws SevenZipException {
                if (writeMode == WriteMode.UPDATE) {
                    if (index == itemToAddOrUpdateIndex) {
                        IOutItemAllFormats item = outItemFactory.createOutItemAndCloneProperties(index);

                        // Change content
                        item.setUpdateIsNewData(true);
                        item.setDataSize((long) itemToAddOrUpdateContent.length);

                        return item;
                    }

                    return outItemFactory.createOutItem(index);
                } else {
                    if (index == itemToAddOrUpdateIndex) {
                        // Adding new item
                        IOutItemAllFormats outItem = outItemFactory.createOutItem();
                        outItem.setPropertyPath(itemToAddOrUpdatePath);
                        outItem.setDataSize((long) itemToAddOrUpdateContent.length);

                        return outItem;
                    }

                    return outItemFactory.createOutItem(index + 1);
                }
            }

            public ISequentialInStream getStream(int i) {
                if (i != itemToAddOrUpdateIndex) {
                    return null;
                }
                return new ByteArrayStream(itemToAddOrUpdateContent, true);
            }
        }
    }
}