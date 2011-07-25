/**
 * Copyright (C) 2010, FuseSource Corp.  All rights reserved.
 */
package org.fusesource.fabric.fab.osgi.url.internal;

import org.fusesource.fabric.fab.ModuleDescriptor;
import org.fusesource.fabric.fab.ModuleRegistry;
import org.fusesource.fabric.fab.VersionedDependencyId;
import org.fusesource.fabric.fab.osgi.url.ServiceConstants;
import org.osgi.framework.Bundle;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.*;
import java.net.URL;
import java.util.*;

import static org.fusesource.fabric.fab.util.Strings.*;
/**
 * <p>
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class OsgiModuleRegistry extends ModuleRegistry {

    File directory;
    ConfigurationAdmin configurationAdmin;
    String pid;

    public OsgiModuleRegistry() {
        Activator.registry = this;
    }

    public File getDirectory() {
        return directory;
    }

    public void setDirectory(File directory) {
        this.directory = directory;
    }

    public ConfigurationAdmin getConfigurationAdmin() {
        return configurationAdmin;
    }

    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    public String getPid() {
        return pid;
    }

    public void setPid(String pid) {
        this.pid = pid;
    }

    public File getLocalIndexDir() {
        return new File(directory, "local");
    }

    HashMap<String, URL> repos = new HashMap<String, URL>();

    public void load() {
        loadRepoConfiguration();

        boolean download = false;
        for (Map.Entry<String, URL> entry: repos.entrySet()){
            String name = entry.getKey();
            URL url = entry.getValue();

            File file = new File(directory, "repo-"+name + ".zip");
            if( !"file".equals(url.getProtocol()) && !file.exists()) {
                download = true;
            }
        }

        update(System.err, download);
    }

    private void loadRepoConfiguration() {
        repos.clear();
        try {
            Dictionary config = getConfig();
            Enumeration elements = config.keys();
            while (elements.hasMoreElements()) {
                String key = (String) elements.nextElement();
                if( key.startsWith("repo.") ) {
                    String name = key.substring("repo.".length());
                    String value = (String) config.get(key);
                    repos.put(name, new URL(value));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
      long rc = 0;
      byte[] buffer = new byte[8192];
      int bytes = in.read(buffer);
      while (bytes >= 0) {
        out.write(buffer, 0, bytes);
        rc += bytes;
        bytes = in.read(buffer);
      }
      return rc;
    }

    /**
     * Updates the repo sources.
     *
     * @param err
     */
    public void update(PrintStream err) {
        loadRepoConfiguration();
        update(err, true);
    }

    /**
     * Updates the repo sources.
     *
     * @param err
     */
    public void update(PrintStream err, boolean download) {
        clear();
        getLocalIndexDir().mkdirs();
        loadDirectory(getLocalIndexDir(), err, true);

        for (Map.Entry<String, URL> entry: repos.entrySet()){
            String name = entry.getKey();
            URL url = entry.getValue();

            File file = new File(directory, "repo-"+name + ".zip");

            if( "file".equals(url.getProtocol()) ) {
                file = new File(url.getFile());
            } else {
                if( download ) {
                    err.println("Downloading: "+url);
                    try {
                        InputStream is = url.openStream();
                        try {
                            FileOutputStream os = new FileOutputStream(file);
                            try {
                                copy(is, os);
                            } finally {
                              os.close();
                            }

                        } finally{
                            is.close();
                        }
                    } catch (IOException e) {
                        if( err!=null ) {
                            err.println(String.format("Error occurred while downloading '%s'.  Error: %s", url, e));
                        }
                    }
                }
            }

            if( file.isDirectory() ) {
                loadDirectory(file, err);
            } else {
                try {
                    loadJar(file);
                } catch (IOException e) {
                    if( err!=null ) {
                        err.println(String.format("Error occurred while loading repo '%s'.  Error: %s", name, e));
                    }
                }
            }

        }

    }

    /**
     * Store extension configuration in the config admin.
     *
     * @param id
     * @return
     */
    @Override
    protected List<String> getEnabledExtensions(VersionedDependencyId id) {
        try {
            String value = getConfigProperty("extensions."+id);
            if (value == null) return null;
            return splitAndTrimAsList(value, " ");
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Store extension configuration in the config admin.
     *
     * @param id
     * @return
     */
    @Override
    protected void setEnabledExtensions(VersionedDependencyId id, List<String> values) {
        setConfigProperty("extensions."+id.toString(), join(values, " "));
    }

    private String getConfigProperty(String key) throws IOException {
        Configuration configuration = configurationAdmin.getConfiguration(pid);
        Dictionary props = configuration.getProperties();
        if( props == null ) {
            return null;
        }
        String value = (String) props.get(key);
        if( value==null ) {
            return null;
        }
        return value;
    }

    private Dictionary getConfig() throws IOException {
        Configuration configuration = configurationAdmin.getConfiguration(pid);
        Dictionary props = configuration.getProperties();
        if( props == null ) {
            return new Hashtable();
        }
        return props;
    }

    protected void setConfigProperty(String key, String value) {
        try {
            Configuration configuration = configurationAdmin.getConfiguration(pid);
            Dictionary props = configuration.getProperties();
            if( props==null ) {
                props = new Hashtable();
            }
            props.put(key, value);
            configuration.update(props);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @Override
    public VersionedModule add(ModuleDescriptor descriptor) {
        try {
            // Store the module descriptor in a file..
            String path = descriptor.getId().getRepositoryPath()+".fmd";
            File file = new File(getLocalIndexDir(), path);
            file.getParentFile().mkdirs();
            Properties props = descriptor.toProperties();
            FileOutputStream os = new FileOutputStream(file);
            try {
                props.store(os, null);
            } finally {
                os.close();
            }

            return super.add(descriptor, file);
        } catch (IOException e) {
            e.printStackTrace();;
            return null;
        }
    }

    @Override
    public VersionedModule remove(VersionedDependencyId id) {
        VersionedModule rc = getVersionedModule(id);
        if( rc!=null && rc.getFile()!=null ) {
            rc = super.remove(id);
            rc.getFile().delete();
        }
        return rc;
    }

    public Map<VersionedDependencyId, Bundle> getInstalled() {
        Map<VersionedDependencyId, Bundle> rc = new HashMap<VersionedDependencyId, Bundle>();
        for (Bundle bundle : Activator.getInstanceBundleContext().getBundles()) {
            String value = (String) bundle.getHeaders().get(ServiceConstants.INSTR_FAB_MODULE_ID);
            if( notEmpty(value) ) {
                try {
                    rc.put(VersionedDependencyId.fromString(value), bundle);
                } catch (Throwable e) {
                }
            }
        }
        return rc;
    }
}
