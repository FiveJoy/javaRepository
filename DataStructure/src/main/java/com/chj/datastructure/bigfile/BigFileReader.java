package com.chj.datastructure.bigfile;

import org.apache.log4j.Logger;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程读取大文件
 */
public class BigFileReader {

    private static Logger logger = Logger.getLogger(BigFileReader.class);

    private int threadSize;
    private String charset;
    private int bufferSize;
    private IHandle handle;
    private ExecutorService executorService;
    private long fileLength;
    private RandomAccessFile rAccessFile;
    private Set<StartEndPair> startEndPairs;
    private CyclicBarrier cyclicBarrier;
    private AtomicLong counter = new AtomicLong(0);

    private BigFileReader(File file, IHandle handle, String charset, int bufferSize, int threadSize) {
        this.fileLength = file.length();
        this.handle = handle;
        this.charset = charset;
        this.bufferSize = bufferSize;
        this.threadSize = threadSize;
        try {
            this.rAccessFile = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        this.executorService = Executors.newFixedThreadPool(threadSize);
        startEndPairs = new HashSet<>();
    }

    public static class Builder {
        private int threadSize = 1;
        private String charset = null;
        private int bufferSize = 1024 * 1024;
        private IHandle handle;
        private File file;

        public Builder(String file, IHandle handle) {
            this.file = new File(file);
            if (!this.file.exists())
                throw new IllegalArgumentException("文件不存在！");
            this.handle = handle;
        }

        public Builder withTreahdSize(int size) {
            this.threadSize = size;
            return this;
        }

        public Builder withCharset(String charset) {
            this.charset = charset;
            return this;
        }

        public Builder withBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public BigFileReader build() {
            return new BigFileReader(this.file, this.handle, this.charset, this.bufferSize, this.threadSize);
        }
    }

    /**
     * 任务开始
     */
    public void start() {
        long everySize = this.fileLength / this.threadSize;
        try {
            calculateStartEnd(0, everySize);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        final long startTime = System.currentTimeMillis();
        cyclicBarrier = new CyclicBarrier(startEndPairs.size(), new Runnable() {

            public void run() {
                logger.info("read file cost: " + (System.currentTimeMillis() - startTime) + "ms");
                logger.info("all line: " + counter.get());
                shutdown();
            }
        });
        for (StartEndPair pair : startEndPairs) {
            logger.info("分配分片：" + pair);
            this.executorService.execute(new SliceReaderTask(pair));
        }
    }

    /**
     * 每一个分片任务
     */
    private class SliceReaderTask implements Runnable {
        private long start;
        private long sliceSize;
        private byte[] readBuff;

        public SliceReaderTask(StartEndPair pair) {
            this.start = pair.start;
            this.sliceSize = pair.end - pair.start + 1;
            this.readBuff = new byte[bufferSize];
        }

        public void run() {
            try {
                MappedByteBuffer mapBuffer = rAccessFile.getChannel().map(MapMode.READ_ONLY, start, this.sliceSize);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                for (int offset = 0; offset < sliceSize; offset += bufferSize) {
                    int readLength;
                    if (offset + bufferSize <= sliceSize) {
                        readLength = bufferSize;
                    } else {
                        readLength = (int) (sliceSize - offset);
                    }
                    mapBuffer.get(readBuff, 0, readLength);
                    for (int i = 0; i < readLength; i++) {
                        byte tmp = readBuff[i];
                        if (tmp == '\n' || tmp == '\r') {
                            handle(bos.toByteArray());
                            bos.reset();
                        } else {
                            bos.write(tmp);
                        }
                    }
                }
                if (bos.size() > 0) {
                    handle(bos.toByteArray());
                }
                cyclicBarrier.await();//同步结束状态
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 计算每一个分片的起止位置
     *
     * @param start
     * @param size
     * @throws IOException
     */
    private void calculateStartEnd(long start, long size) throws IOException {
        if (start > fileLength - 1) {
            return;
        }
        StartEndPair pair = new StartEndPair();
        pair.start = start;
        long endPosition = start + size - 1;
        if (endPosition >= fileLength - 1) {
            pair.end = fileLength - 1;
            startEndPairs.add(pair);
            return;
        }

        rAccessFile.seek(endPosition);
        byte tmp = (byte) rAccessFile.read();
        while (tmp != '\n' && tmp != '\r') {
            endPosition++;
            if (endPosition >= fileLength - 1) {
                endPosition = fileLength - 1;
                break;
            }
            rAccessFile.seek(endPosition);
            tmp = (byte) rAccessFile.read();
        }
        pair.end = endPosition;
        startEndPairs.add(pair);

        calculateStartEnd(endPosition + 1, size);

    }


    /**
     * 关闭
     */
    public void shutdown() {
        try {
            this.rAccessFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.executorService.shutdown();
    }

    /**
     * 处理每一行
     *
     * @param bytes
     * @throws UnsupportedEncodingException
     */
    private void handle(byte[] bytes) throws UnsupportedEncodingException {
        String line = null;
        if (this.charset == null) {
            line = new String(bytes);
        } else {
            line = new String(bytes, charset);
        }
        if (line != null && !"".equals(line)) {
            this.handle.handle(line);//回调
            counter.incrementAndGet();
        }
    }

    /**
     * 开始结束的位置标记
     */
    private static class StartEndPair {
        public long start;
        public long end;

        @Override
        public String toString() {
            return "star=" + start + ";end=" + end;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (int) (end ^ (end >>> 32));
            result = prime * result + (int) (start ^ (start >>> 32));
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            StartEndPair other = (StartEndPair) obj;
            if (end != other.end)
                return false;
            if (start != other.start)
                return false;
            return true;
        }

    }

}
