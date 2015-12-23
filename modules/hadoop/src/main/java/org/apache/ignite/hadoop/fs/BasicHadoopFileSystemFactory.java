/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.hadoop.fs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.ignite.IgniteException;
import org.apache.ignite.internal.processors.hadoop.HadoopUtils;
import org.apache.ignite.internal.processors.igfs.IgfsUtils;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lifecycle.LifecycleAware;
import org.jetbrains.annotations.Nullable;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;

/**
 * Caching Hadoop file system factory. File systems are cache on per-user basis.
 */
public class BasicHadoopFileSystemFactory implements HadoopFileSystemFactory, Externalizable, LifecycleAware {
    /** File system URI. */
    protected String uri;

    /** File system config paths. */
    protected String[] cfgPaths;

    /** Configuration of the secondary filesystem, never null. */
    protected Configuration cfg;

    /** */
    protected URI fullUri;

    /**
     * Public non-arg constructor.
     */
    public BasicHadoopFileSystemFactory() {
        // noop
    }

    /** {@inheritDoc} */
    @Override public FileSystem create(String usrName) throws IOException {
        return create0(IgfsUtils.fixUserName(usrName));
    }

    /**
     * Internal file system create routine.
     *
     * @param usrName User name.
     * @return File system.
     * @throws IOException If failed.
     */
    protected FileSystem create0(String usrName) throws IOException {
        assert cfg != null;

        try {
            return FileSystem.get(fullUri, cfg, usrName);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IOException("Failed to create file system due to interrupt.", e);
        }
    }

    /**
     * Gets file system URI.
     *
     * @return File system URI.
     */
    @Nullable public String getUri() {
        return uri;
    }

    /**
     * Sets file system URI.
     *
     * @param uri File system URI.
     */
    public void setUri(@Nullable String uri) {
        this.uri = uri;
    }

    /**
     * Gets paths to additional file system configuration files (e.g. core-site.xml).
     *
     * @return Paths to file system configuration files.
     */
    @Nullable public String[] getConfigPaths() {
        return cfgPaths;
    }

    /**
     * Set paths to additional file system configuration files (e.g. core-site.xml).
     *
     * @param cfgPaths Paths to file system configuration files.
     */
    public void setConfigPaths(String... cfgPaths) {
        this.cfgPaths = cfgPaths;
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteException {
        // if secondary fs URI is not given explicitly, try to get it from the configuration:
        if (uri == null)
            fullUri = FileSystem.getDefaultUri(cfg);
        else {
            try {
                fullUri = new URI(uri);
            }
            catch (URISyntaxException use) {
                throw new IgniteException("Failed to resolve secondary file system URI: " + uri);
            }
        }

        cfg = HadoopUtils.safeCreateConfiguration();

        if (cfgPaths != null) {
            for (String cfgPath : cfgPaths) {
                if (cfgPath == null)
                    throw new IgniteException("Configuration path cannot be null: " + Arrays.toString(cfgPaths));
                else {
                    URL url = U.resolveIgniteUrl(cfgPath);

                    if (url == null) {
                        // If secConfPath is given, it should be resolvable:
                        throw new IgniteException("Failed to resolve secondary file system configuration path " +
                            "(ensure that it exists locally and you have read access to it): " + cfgPath);
                    }

                    cfg.addResource(url);
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void stop() throws IgniteException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        U.writeString(out, uri);

        if (cfgPaths != null) {
            out.writeInt(cfgPaths.length);

            for (String cfgPath : cfgPaths)
                U.writeString(out, cfgPath);
        }
        else
            out.writeInt(-1);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        uri = U.readString(in);

        int cfgPathsCnt = in.readInt();

        if (cfgPathsCnt != -1) {
            cfgPaths = new String[cfgPathsCnt];

            for (int i = 0; i < cfgPathsCnt; i++)
                cfgPaths[i] = U.readString(in);
        }
    }
}
