/*
 * Copyright (c) 2020 MarkLogic Corporation
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
package com.marklogic.contentpump;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.mapreduce.InputSplit;

/**
 * Content Pump base mapper with the capability to run in thread-safe mode.
 * 
 * @author jchen
 *
 * @param <KEYIN>
 * @param <VALUEIN>
 * @param <KEYOUT>
 * @param <VALUEOUT>
 */
public class BaseMapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT> extends
    org.apache.hadoop.mapreduce.Mapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {
    public static final Log LOG = LogFactory.getLog(BaseMapper.class);    
    public void runThreadSafe(Context outerCtx, Context subCtx)
        throws IOException, InterruptedException {
        setup(subCtx);
        KEYIN key = null;
        VALUEIN value = null;
        try {
            while (!ContentPump.shutdown) {
                synchronized (outerCtx) {
                    if (!subCtx.nextKeyValue()) {
                        break;
                    }
                    key = subCtx.getCurrentKey();
                    value = subCtx.getCurrentValue();
                }
                map(key, value, subCtx);
            }
        } finally {
            if (ContentPump.shutdown && LOG.isDebugEnabled()) {
                LOG.debug("Aborting task...");
            }
            cleanup(subCtx);
        }
    }
    
    public int getRequiredThreads() {
        return 1;
    }
    
    public List<Future<Object>> submitTasks(ExecutorService threadPool,
            InputSplit inputSplit) {
        return Collections.emptyList();
    }
    
    @Override
    public void run(Context context) throws IOException, InterruptedException {
        setup(context);
        try {
            while (!ContentPump.shutdown && context.nextKeyValue()) {
                map(context.getCurrentKey(), context.getCurrentValue(), context);
            }
        } finally {
            if (ContentPump.shutdown && LOG.isDebugEnabled()) {
                LOG.debug("Aborting task...");
            }
            cleanup(context);
        }
    }
}
