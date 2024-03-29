package org.apache.hadoop.ozone.freon;

import com.codahale.metrics.Timer;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import org.slf4j.Logger;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.concurrent.Callable;


@CommandLine.Command(name = "wtb",
        aliases = "write-throughput-benchmark",
        description = "Benchmark for creating a file",
        versionProvider = HddsVersionProvider.class,
        mixinStandardHelpOptions = true,
        showDefaultValues = true)
public class WriteFileThroughputBenchmark extends BaseFreonGenerator
        implements Callable<Void>{

    @Option(names = {"-o"},
            description = "Ozone filesystem path",
            defaultValue = "o3fs://bucket1.vol1")
    private String rootPath;

    @Option(names = {"-s", "--size"},
            description = "Size of each generated files (in GB)",
            defaultValue = "1")
    private long fileSize;

    @Option(names = {"-bl", "--block"},
            description = "Specify the Block Size in MB",
            defaultValue = "128")
    private long blockSize;

    @Option(names = {"-bu", "--buffer"},
            description = "Size of buffer used store the generated " +
                    "key content",
            defaultValue = "10240")
    private int bufferSize;

    @Option(names = {"-th", "--throttle"},
            description = "Specify the Delay in Input/Output",
            defaultValue = "0")
    private int throttle;

    @Option(names = {"-re", "--replication"},
            description = "Specify the Replication factor",
            defaultValue = "1")
    private short replication;

    @Option(names= {"--sync"},
            description = "Optionally Issue hsync after every write " +
                    "Cannot be used with hflush",
            defaultValue = "false"
    )
    private boolean hSync;

    @Option(names= {"--flush"},
            description = "Optionally Issue hsync after every write " +
                    "Cannot be used with hflush",
            defaultValue = "false"
    )
    private boolean hFlush;

    // For Generating the content of the files
    private ContentGenerator contentGenerator;
    // For Creating the required configurations for the file system
    private OzoneConfiguration configuration;

    private URI uri;

    // variable to check
    private boolean isThrottled;

    long expectedIoTimeNs;

    private Timer timer;

    private final ThreadLocal<FileSystem> threadLocalFileSystem =
            ThreadLocal.withInitial(this::createFS);

    public static final Logger LOG =
            LoggerFactory.getLogger(WriteFileThroughputBenchmark.class);

    // Checking whether an output directory is created inside the bucket
    private static void ensureOutputDirExists(FileSystem fs, Path outputDir)
            throws IOException {
        if (!fs.exists(outputDir)) {
            LOG.error("No Such Output Directory exists : {}", outputDir);
            System.exit(1);
        }
    }


    public Void call() throws Exception{

        LOG.info("NumFiles=" + getTestNo());
        LOG.info("Total FileSize=" + fileSize);
        LOG.info("BlockSize=" + blockSize);
        LOG.info("BufferSize=" + bufferSize);
        LOG.info("Replication=" + replication);
        LOG.info("Threads=" + getThreadNo());
        LOG.info("URI Scheme Used=" + uri.getScheme());
        if(hSync){
            LOG.info("Hsync after every write= True");
        }
        else if(hFlush){
            LOG.info("Hflush after every write= True");
        }

        // Initialize the configuration variable with
        // OzoneFS configuration
        configuration = createOzoneConfiguration();

        //Constructs a URI object by parsing the given string rootPath
        uri = URI.create(rootPath);

        // Disabling the file system cache
        String disableCacheName = String.format("fs.%s.impl.disable.cache",
                uri.getScheme());
        print("Disabling FS cache: " + disableCacheName);
        configuration.setBoolean(disableCacheName, true);

        Path file = new Path(rootPath + "/" +
                generateObjectName(0));
        try (FileSystem fileSystem = threadLocalFileSystem.get()) {
            fileSystem.mkdirs(file.getParent());
        }
        // Checks whether output directory exists
        ensureOutputDirExists(createFS(),file);

        // Initialize the size of the file to be written in Bytes
        long filesizeinBytes = fileSize*1_000_000_000;
        contentGenerator =  new ContentGenerator(filesizeinBytes,
                bufferSize, hSync, hFlush);

        expectedIoTimeNs =
                (isThrottled ? (((long) bufferSize * 1_000_000_000) / throttle)
                        : 0);

        timer = getMetrics().timer("file-create");

        runTests(this::createFile);

        return null;
    }


    private void createFile(long counter) throws Exception {
        Path file = new Path(rootPath + "/" + generateObjectName(counter));
        FileSystem fileSystem = threadLocalFileSystem.get();

        final long ioStartTimeNs = (isThrottled ? System.nanoTime() : 0);

        timer.time(() -> {
            try ( FSDataOutputStream outputStream = fileSystem.create(file,
                    false, bufferSize,replication,blockSize);) {
                contentGenerator.write(outputStream);

                // Enforcing throttle delay
                final long ioEndTimeNs = (isThrottled ? System.nanoTime() : 0);
                enforceThrottle(ioEndTimeNs - ioStartTimeNs, expectedIoTimeNs);
            }
            return null;
        });
    }

    private FileSystem createFS() {
        try {
            return FileSystem.get(uri, configuration);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Method to cause the delay of a certain amount
    static void enforceThrottle(long ioTimeNs, long expectedIoTimeNs)
            throws InterruptedException {
        if (ioTimeNs < expectedIoTimeNs) {
            // The IO completed too fast, so sleep for some time.
            long sleepTimeNs = expectedIoTimeNs - ioTimeNs;
            Thread.sleep(sleepTimeNs / 1_000_000, (int)
                    (sleepTimeNs % 1_000_000));
        }
    }
    @Override
    protected void taskLoopCompleted() {
        FileSystem fileSystem = threadLocalFileSystem.get();
        try {
            fileSystem.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
