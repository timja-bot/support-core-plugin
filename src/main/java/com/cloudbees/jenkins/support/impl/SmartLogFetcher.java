package com.cloudbees.jenkins.support.impl;

import com.cloudbees.jenkins.support.SupportPlugin;
import com.cloudbees.jenkins.support.util.StreamUtils;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.MasterToSlaveFileCallable;

/**
 * Efficient incremental retrieval of log files from {@link Node}, by taking advantages of
 * the additive nature of log files.
 *
 * <p>
 * Log files tend to get its data appended to the end, so for each file, we look at
 * what we already locally have, and see if the remote file has the exact same header section.
 * If this is the case, we only need to transfer the tail section of it, which cuts the amount
 * of data transfer significantly.
 *
 * @author Stephen Connolly
 */
class SmartLogFetcher {
    private final File rootCacheDir;
    private final FilenameFilter filter;

    /**
     * @param id
     *      SmartLogFetcher only supports one directory full of log files retrieved in one go.
     *      So different IDs would have to be specified for different log files from different directories.
     * @param filter
     *      Used to match log files within the target directory.
     */
    public SmartLogFetcher(String id, FilenameFilter filter) {
        this.rootCacheDir = new File(SupportPlugin.getLogsDirectory(), id);
        this.filter = filter;
        assert filter instanceof Serializable;
    }

    public ForNode forNode(Node n) throws IOException {
        return new ForNode(n);
    }

    class ForNode {
        private final Node node;

        /**
         * Directory on this machine we use to cache log files available on the given node.
         */
        private final File cacheDir;

        ForNode(Node node) throws IOException {
            this.node = node;

            String cacheKey = Util.getDigestOf(node.getNodeName() + ":" + node.getRootPath()); //FIPS OK: Not security related.
            this.cacheDir = new File(rootCacheDir, StringUtils.right(cacheKey, 8));

            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                throw new IOException("Could not create local cache directory: " + cacheDir);
            }
        }

        /**
         * Retrieves all the log files in the given remote directory into local directory,
         * then return them as a map keyed by relative file names from {@code remoteDir}.
         */
        public Map<String,File> getLogFiles(FilePath remoteDir)
                throws InterruptedException, IOException {
            File localCache = cacheDir;

            // build an inventory of what we already have locally
            final Map<String, FileHash> hashes = new LinkedHashMap<String, FileHash>();
            final File[] localCacheFiles = localCache.listFiles(filter);
            if (localCacheFiles != null) {
                for (File file : localCacheFiles) {
                    hashes.put(file.getName(), new FileHash(file));
                }
            }

            // figure out what we need to read
            Map<String,Long> offsets = remoteDir.act(new LogFileHashSlurper(hashes, filter));

            evictDeadCache(hashes, offsets);

            // then read those
            Map<String,File> result = new LinkedHashMap<String, File>();
            for (Map.Entry<String, Long> entry : offsets.entrySet()) {
                File local = new File(localCache, entry.getKey());
                if (entry.getValue() > 0 && local.isFile()) {
                    final long localLength = local.length();
                    if (entry.getValue() < Long.MAX_VALUE) {
                        // only copy the new content
                        try (FileOutputStream fos = new FileOutputStream(local, true);
                             InputStream is = remoteDir.child(entry.getKey()).readFromOffset(localLength)) {
                            IOUtils.copy(is, fos);
                        }
                    }
                    result.put(entry.getKey(), local);
                } else {
                    try (FileOutputStream fos = new FileOutputStream(local, false);
                         InputStream is = remoteDir.child(entry.getKey()).read()) {
                        IOUtils.copy(is, fos);
                    }
                    result.put(entry.getKey(), local);
                }
            }
            return result;
        }

        private void evictDeadCache(Map<String, FileHash> hashes, Map<String, Long> offsets) {
            for (String key: hashes.keySet()) {
                if (offsets.containsKey(key))
                    continue;   // still exists on the agent

                final File deadCacheFile = new File(cacheDir, key);
                if (!deadCacheFile.delete()) {
                    LOGGER.log(Level.WARNING, "Unable to delete redundant cache file: {0}", deadCacheFile);
                }
            }
        }
    }

    /**
     * MD5 checksum of a head section of a file.
     */
    public static final class FileHash implements Serializable {
        private static final long serialVersionUID = 1L;
        private static final String ZERO_LENGTH_MD5 = "d41d8cd98f00b204e9800998ecf8427e";
        private final long length;
        private final String md5;

        public FileHash(long length, String md5) {
            this.length = length;
            this.md5 = md5;
        }

        public FileHash(File file) throws IOException {
            this.length = file.length();
            this.md5 = getDigestOf(new FileInputStream(file), length);
        }

        public FileHash(FilePath file) throws IOException, InterruptedException {
            this.length = file.length();
            this.md5 = getDigestOf(file.read(), length);
        }

        public long getLength() {
            return length;
        }

        public String getMd5() {
            return md5;
        }

        /**
         * Does the given file has the same head section as this file hash?
         */
        public boolean isPartialMatch(File file) throws IOException {
            if (file.length() < length) return false;
            return md5.equals(getDigestOf(new FileInputStream(file), length));
        }

        /**
         * Computes the checksum of a stream upto the specified length.
         */
        public static String getDigestOf(InputStream stream, long length) throws IOException { //FIPS OK: Not security related.
            try {
                if (length == 0 || stream == null) return ZERO_LENGTH_MD5;
                int bufferSize;
                if (length < 8192L) bufferSize = (int) length;
                else if (length > 65536L) bufferSize = 65536;
                else bufferSize = 8192;
                byte[] buffer = new byte[bufferSize];
                MessageDigest digest = null;
                try {
                    digest = MessageDigest.getInstance("md5"); //FIPS OK: Not security related.
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("Java Language Specification mandates MD5 as a supported digest",
                            e);
                }
                int read;
                while (length > 0 && (read = stream.read(buffer, 0, (int) Math.min(bufferSize, length))) != -1) {
                    digest.update(buffer, 0, read);
                    length -= read;
                }
                return Hex.encodeHexString(digest.digest());
            } finally {
                StreamUtils.closeQuietly(stream);
            }
        }
    }

    /**
     * Takes what we already cached on the controller, then figure out what needs to be transferred back.
     *
     * <p>
     * Returns the information as a tuple of (relative file name from the directory, offset that needs to be read)
     */
    public static final class LogFileHashSlurper extends MasterToSlaveFileCallable<Map<String,Long>> {
        /**
         * What we already cached on the controller side.
         */
        private final Map<String,FileHash> cached;

        private final FilenameFilter filter;

        public LogFileHashSlurper(Map<String, FileHash> cached, FilenameFilter filter) {
            this.cached = cached;
            this.filter = filter;
        }

        public Map<String, Long> invoke(File dir, VirtualChannel channel) throws IOException, InterruptedException {
            Map<String, Long> result = new LinkedHashMap<String, Long>();
            File[] files = dir.listFiles(filter);
            if (files == null) {
                return result;
            }
            for (File file : files) {
                FileHash hash = cached.get(file.getName());
                if (hash != null && hash.isPartialMatch(file)) {
                    if (file.length() == hash.getLength()) {
                        result.put(file.getName(), Long.MAX_VALUE); // indicate have everything
                    } else {
                        result.put(file.getName(), hash.getLength());
                    }
                } else {
                    // read the whole thing
                    result.put(file.getName(), 0L);
                }
            }
            return result;
        }

    }

    private static final Logger LOGGER = Logger.getLogger(SmartLogFetcher.class.getName());
}
