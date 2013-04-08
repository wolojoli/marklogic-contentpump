/*
 * Copyright 2003-2013 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.mapreduce;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.marklogic.mapreduce.utilities.AssignmentManager;
import com.marklogic.mapreduce.utilities.AssignmentPolicy;
import com.marklogic.mapreduce.utilities.StatisticalAssignmentPolicy;
import com.marklogic.xcc.Content;
import com.marklogic.xcc.ContentCapability;
import com.marklogic.xcc.ContentCreateOptions;
import com.marklogic.xcc.ContentFactory;
import com.marklogic.xcc.ContentPermission;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.DocumentFormat;
import com.marklogic.xcc.DocumentRepairLevel;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.Session.TransactionMode;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.RequestPermissionException;
import com.marklogic.xcc.exceptions.RequestServerException;
import com.marklogic.xcc.exceptions.ServerConnectionException;
import com.marklogic.xcc.exceptions.XQueryException;

/**
 * MarkLogicRecordWriter that inserts content to MarkLogicServer.
 * 
 * @author jchen
 *
 */
public class ContentWriter<VALUEOUT> 
extends MarkLogicRecordWriter<DocumentURI, VALUEOUT> implements MarkLogicConstants {
    public static final Log LOG = LogFactory.getLog(ContentWriter.class);
    
    /**
     * Directory of the output documents.
     */
    private String outputDir;
    
    /**
     * Content options of the output documents.
     */
    private ContentCreateOptions options;
    
    /**
     * A map from a forest id to a ContentSource. 
     */
    private Map<String, ContentSource> forestSourceMap;
    
    /**
     * Content lists for each forest.
     */
    private Content[][] forestContents;
    
    /**
     * An array of forest ids
     */
    private String[] forestIds;
    
    /** 
     * Counts of documents per forest.
     */
    private int[] counts;
    
    /**
     * Whether in fast load mode.
     */
    private boolean fastLoad;
    
    /**
     * Batch size.
     */
    private int batchSize;
    
    /**
     * Counts of requests per forest.
     */
    private int[] stmtCounts;
    
    /**
     * Sessions per forest.
     */
    private Session[] sessions;
    
    private boolean formatNeeded;
    
    private FileSystem fs;
    
    private InputStream is;
    
    private boolean streaming;
    
    private boolean tolerateErrors;

    private AssignmentManager am;
    
    private long []docCount;
    //default boolean is false
    private boolean needDocCount;
    
    public ContentWriter(Configuration conf, 
        Map<String, ContentSource> forestSourceMap, boolean fastLoad) {
        this(conf, forestSourceMap, fastLoad, null);
    }
    
    public ContentWriter(Configuration conf, 
            Map<String, ContentSource> forestSourceMap, boolean fastLoad, AssignmentManager am) {
        super(conf, null);
        
        this.fastLoad = fastLoad;
        
        this.forestSourceMap = forestSourceMap;
        
        this.am = am;
        // arraySize is the number of forests in fast load mode; 1 otherwise.
        int arraySize = forestSourceMap.size();
        forestIds = new String[arraySize];
        // key order in key set is guaranteed by LinkedHashMap,
        // i.e., the order keys are inserted
        forestIds = forestSourceMap.keySet().toArray(forestIds);
        sessions = new Session[arraySize];
        stmtCounts = new int[arraySize];
        
        outputDir = conf.get(OUTPUT_DIRECTORY);
        batchSize = conf.getInt(BATCH_SIZE, DEFAULT_BATCH_SIZE);
        if (batchSize > 1) {           
            forestContents = new Content[arraySize][batchSize];
            counts = new int[arraySize];
        }
        if (fastLoad
            && am.getPolicy().getPolicyKind() == AssignmentPolicy.Kind.STATISTICAL) {
            docCount = new long[arraySize];
            needDocCount = true;
        }
        
        String[] perms = conf.getStrings(OUTPUT_PERMISSION);
        List<ContentPermission> permissions = null;
        if (perms != null && perms.length > 0) {
            int i = 0;
            while (i + 1 < perms.length) {
                String roleName = perms[i++];
                if (roleName == null || roleName.isEmpty()) {
                    LOG.error("Illegal role name: " + roleName);
                    continue;
                }
                String perm = perms[i].trim();
                ContentCapability capability = null;
                if (perm.equalsIgnoreCase(ContentCapability.READ.toString())) {
                    capability = ContentCapability.READ;
                } else if (perm.equalsIgnoreCase(ContentCapability.EXECUTE.toString())) {
                    capability = ContentCapability.EXECUTE;
                } else if (perm.equalsIgnoreCase(ContentCapability.INSERT.toString())) {
                    capability = ContentCapability.INSERT;
                } else if (perm.equalsIgnoreCase(ContentCapability.UPDATE.toString())) {
                    capability = ContentCapability.UPDATE;
                } else {
                    LOG.error("Illegal permission: " + perm);
                }
                if (capability != null) {
                    if (permissions == null) {
                        permissions = new ArrayList<ContentPermission>();
                    }
                    permissions.add(new ContentPermission(capability, roleName));
                }
                i++;
            }
        }
        
        options = new ContentCreateOptions();
        String[] collections = conf.getStrings(OUTPUT_COLLECTION);
        if (collections != null) {
            for (int i = 0; i < collections.length; i++) {
                collections[i] = collections[i].trim();
            }
            options.setCollections(collections);
        }
        
        options.setQuality(conf.getInt(OUTPUT_QUALITY, 0));
        if (permissions != null) {
            options.setPermissions(permissions.toArray(
                    new ContentPermission[permissions.size()]));
        } 
        String contentTypeStr = conf.get(CONTENT_TYPE, DEFAULT_CONTENT_TYPE);
        ContentType contentType = ContentType.valueOf(contentTypeStr);
        if (contentType == ContentType.UNKNOWN) {
            formatNeeded = true;
        } else {
            options.setFormat(contentType.getDocumentFormat());
        }
        
        options.setLanguage(conf.get(OUTPUT_CONTENT_LANGUAGE));
        String repairLevel = conf.get(OUTPUT_XML_REPAIR_LEVEL,
                DEFAULT_OUTPUT_XML_REPAIR_LEVEL).toLowerCase();
        options.setNamespace(conf.get(OUTPUT_CONTENT_NAMESPACE));
        if (DocumentRepairLevel.DEFAULT.toString().equals(repairLevel)){
            options.setRepairLevel(DocumentRepairLevel.DEFAULT);
        }
        else if (DocumentRepairLevel.NONE.toString().equals(repairLevel)){
            options.setRepairLevel(DocumentRepairLevel.NONE);
        }
        else if (DocumentRepairLevel.FULL.toString().equals(repairLevel)){
            options.setRepairLevel(DocumentRepairLevel.FULL);
        }
        
        streaming = conf.getBoolean(OUTPUT_STREAMING, false);
        tolerateErrors = conf.getBoolean(OUTPUT_TOLERATE_ERRORS, false);
        
        String encoding = conf.get(MarkLogicConstants.OUTPUT_CONTENT_ENCODING);
        if (encoding != null) {
            options.setEncoding(encoding);
        }
    }

    @Override
    public void write(DocumentURI key, VALUEOUT value) 
    throws IOException, InterruptedException {
        String uri = key.getUri();
        String forestId = ContentOutputFormat.ID_PREFIX;
        int fId = 0;
        if (fastLoad) {
            // compute forest to write to 
            if (outputDir != null && !outputDir.isEmpty()) {
                uri = outputDir.endsWith("/") || uri.startsWith("/") ? 
                      outputDir + uri : outputDir + '/' + uri;
            }    
            key.setUri(uri);
            key.validate();
            // placement based on assignment policy
            fId = am.getPlacementForestIndex(key);
            forestId = forestIds[fId];
        }
 
        try {
            Content content = null;
            if (value instanceof Text) {
                if (formatNeeded) {
                    options.setFormat(DocumentFormat.TEXT);
                    formatNeeded = false;
                }
                options.setEncoding(DEFAULT_OUTPUT_CONTENT_ENCODING);
                content = ContentFactory.newContent(uri, 
                        ((Text) value).getBytes(), 0, 
                        ((Text)value).getLength(), options);
            } else if (value instanceof MarkLogicNode) {
                if (formatNeeded) {
                    options.setFormat(DocumentFormat.XML);
                    formatNeeded = false;
                }
                content = ContentFactory.newContent(uri, 
                        ((MarkLogicNode)value).get(), options);                 
            } else if (value instanceof BytesWritable) {
                if (formatNeeded) {
                    options.setFormat(DocumentFormat.BINARY);
                    formatNeeded = false;
                }            
                content = ContentFactory.newContent(uri, 
                        ((BytesWritable) value).getBytes(), 0, 
                        ((BytesWritable) value).getLength(), options);               
            } else if (value instanceof CustomContent) { 
                ContentCreateOptions newOptions = options;
                if (batchSize > 1) {
                    newOptions = (ContentCreateOptions)options.clone();
                }
                content = ((CustomContent) value).getContent(conf, newOptions, 
                        uri);
            } else if (value instanceof MarkLogicDocument) {
                MarkLogicDocument doc = (MarkLogicDocument)value;
                if (formatNeeded) {
                    options.setFormat(doc.getContentType().getDocumentFormat());
                    formatNeeded = false;
                }
                options.setEncoding(DEFAULT_OUTPUT_CONTENT_ENCODING);
                if (doc.getContentType() == ContentType.BINARY) {
                    content = ContentFactory.newContent(uri, 
                              doc.getContentAsByteArray(), options);
                } else {
                    content = ContentFactory.newContent(uri, 
                              doc.getContentAsText().getBytes(), options);
                }
            } else if (value instanceof StreamLocator) {
                Path path = ((StreamLocator)value).getPath();
                if (fs == null) {         
                    URI fileUri = path.toUri();
                    fs = FileSystem.get(fileUri, conf);
                }
                switch (((StreamLocator)value).getCodec()) {
                    case GZIP:
                        InputStream fileIn = fs.open(path);
                        is = new GZIPInputStream(fileIn);
                        break;
                    case ZIP:
                        if (is == null) {
                            InputStream zipfileIn = fs.open(path);
                            ZipInputStream zis = new ZipInputStream(zipfileIn);
                            is = new ZipEntryInputStream(zis, path.toString());
                        }
                        break;
                    case NONE:
                        is = fs.open(path);
                        break;
                    default:
                        LOG.error("Unsupported compression codec: " + value);
                        return;
                }
                if (streaming) {
                    content = ContentFactory.newUnBufferedContent(uri, is, 
                            options);
                } else {
                    content = ContentFactory.newContent(uri, is, options);
                }
                
            } else {
                throw new UnsupportedOperationException(value.getClass()
                    + " is not supported.");
            }
            if (batchSize > 1) {
                forestContents[fId][counts[fId]++] = content;
 
                if (counts[fId] == batchSize) {
                    if (sessions[fId] == null) {
                        sessions[fId] = getSession(forestId);
                    }        
                    List<RequestException> errors = 
                        sessions[fId].insertContentCollectErrors(
                                forestContents[fId]);
                    if (errors != null) {
                        for (RequestException ex : errors) {
                            if (ex instanceof XQueryException) {
                                LOG.warn(((XQueryException) ex).getFormatString());
                            } else {
                                LOG.warn(ex.getMessage());
                            }
                        }
                    }
                    stmtCounts[fId]++;
                    counts[fId] = 0;
                    //update doc count for statistical
                    if (needDocCount) {
                        updateDocCount(fId, batchSize
                            - (errors != null ? errors.size() : 0));
                    }
                }
            } else {
                if (sessions[fId] == null) {
                    sessions[fId] = getSession(forestId);
                }
                sessions[fId].insertContent(content);
                stmtCounts[fId]++;
                //update doc count for statistical
                if (needDocCount) {
                    updateDocCount(fId, 1);
                }
            }
            if (stmtCounts[fId] == txnSize && 
                sessions[fId].getTransactionMode() == TransactionMode.UPDATE) {
                sessions[fId].commit();
                stmtCounts[fId] = 0;
                if (needDocCount) {
                    docCount[fId] = 0;
                }
            }
        } catch (ServerConnectionException e) {
            if (sessions[fId] != null) {
                sessions[fId].close();
            }
            if (needDocCount) {
                rollbackDocCount(fId);
            }
            throw new IOException(e);
        } catch (RequestPermissionException e) {
            if (sessions[fId] != null) {
                sessions[fId].close();
            }
            if (needDocCount) {
                rollbackDocCount(fId);
            }
            throw new IOException(e);
        } catch (RequestServerException e) {
            // log error and continue on RequestServerException
            if (e instanceof XQueryException) {
                LOG.warn(((XQueryException) e).getFormatString());
            } else {
                LOG.warn(e.getMessage());
            }
        } catch (RequestException e) {
            if (sessions[fId] != null) {
                sessions[fId].close();
            }
            if (needDocCount) {
                rollbackDocCount(fId);
            }
            throw new IOException(e);
        }
    }

    /**
     * 
     * @param fId forest index
     * @param count count of newly added docs
     */
    private void updateDocCount(int fId, int count) {
        StatisticalAssignmentPolicy sap = (StatisticalAssignmentPolicy) am
            .getPolicy();
        docCount[fId] += count;
        sap.updateStats(fId, count);
    }

    private void rollbackDocCount(int fId) {
        StatisticalAssignmentPolicy sap = (StatisticalAssignmentPolicy) am
            .getPolicy();
        sap.updateStats(fId, -docCount[fId]);
        LOG.error("rollback doc count of forest " + fId + ":" + docCount[fId]);
    }
    
    private Session getSession(String forestId) {
        Session session = null;
        ContentSource cs = forestSourceMap.get(forestId);
        if (fastLoad) {
            session = cs.newSession(forestId);
            
        } else {
            session = cs.newSession();
        }      
        if (txnSize > 1 || (batchSize > 1 && tolerateErrors)) {
            session.setTransactionMode(TransactionMode.UPDATE);
        }
        return session;
    }

    @Override
    public void close(TaskAttemptContext context) throws IOException,
    InterruptedException {
        if (batchSize > 1) {
            for (int i = 0; i < forestIds.length; i++) {
                if (counts[i] > 0) {
                    Content[] remainder = new Content[counts[i]];
                    System.arraycopy(forestContents[i], 0, remainder, 0, 
                            counts[i]);
                    
                    if (sessions[i] == null) {
                        String forestId = forestIds[i];
                        sessions[i] = getSession(forestId);
                    }
                                          
                    try {
                        List<RequestException> errors = 
                            sessions[i].insertContentCollectErrors(remainder);
                        if (errors != null) {
                            for (RequestException ex : errors) {
                                if (ex instanceof XQueryException) {
                                    LOG.warn(((XQueryException) ex).
                                            getFormatString());
                                } else {
                                    LOG.warn(ex.getMessage());
                                }
                            }    
                        }
                        stmtCounts[i]++;     
                        //RequestException if any is thrown before docCount is updated
                        //so docCount doesn't need to rollback in this try-catch
                        if (needDocCount) {
                            updateDocCount(i, counts[i]
                                - (errors != null ? errors.size() : 0));
                        }
                    } catch (RequestException e) {
                        LOG.error(e);
                        if (sessions[i] != null) {
                            sessions[i].close();
                        }
                        if (e instanceof ServerConnectionException
                            || e instanceof RequestPermissionException) {
                            throw new IOException(e);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < sessions.length; i++) {
            if (sessions[i] != null) {
                if (stmtCounts[i] > 0
                    && sessions[i].getTransactionMode() == TransactionMode.UPDATE) {
                    try {
                        sessions[i].commit();
                    } catch (RequestException e) {
                        LOG.error(e);
                        if (needDocCount) {
                            rollbackDocCount(i);
                        }
                        throw new IOException(e);
                    } finally {
                        sessions[i].close();
                    }
                } else {
                    sessions[i].close();
                }
            }
        }
        if (is != null) {
            is.close();
            if (is instanceof ZipEntryInputStream) {
                ((ZipEntryInputStream)is).closeZipInputStream();
            }
        }
    }
    
    @Override
    public int getTransactionSize(Configuration conf) {
        // return the specified txn size
        if (conf.get(TXN_SIZE) != null) {
            int txnSize = conf.getInt(TXN_SIZE, 0);
            return txnSize <= 0 ? 1 : txnSize;
        } 
        return 1000 / conf.getInt(BATCH_SIZE, DEFAULT_BATCH_SIZE);
    }
}
