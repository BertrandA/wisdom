package org.wisdom.maven.utils;

import aQute.bnd.osgi.Analyzer;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Converts Maven metadata to OSGi metadata.
 * (from the maven-bundle-plugin)
 *
 * @author <a href="mailto:carlos@apache.org">Carlos Sanchez</a>
 * @version $Id: DefaultMaven2OsgiConverter.java 661727 2008-05-30 14:21:49Z bentmann $
 */
public class DefaultMaven2OsgiConverter {

    private static final String FILE_SEPARATOR = System.getProperty("file.separator");


    private String getBundleSymbolicName(String groupId, String artifactId) {
        return groupId + "." + artifactId;
    }


    /**
     * Get the symbolic name as groupId + "." + artifactId, with the following exceptions
     * <ul>
     * <li>if artifact.getFile is not null and the jar contains a OSGi Manifest with
     * Bundle-SymbolicName property then that value is returned</li>
     * <li>if groupId has only one section (no dots) and artifact.getFile is not null then the
     * first package name with classes is returned. eg. commons-logging:commons-logging ->
     * org.apache.commons.logging</li>
     * <li>if artifactId is equal to last section of groupId then groupId is returned. eg.
     * org.apache.maven:maven -> org.apache.maven</li>
     * <li>if artifactId starts with last section of groupId that portion is removed. eg.
     * org.apache.maven:maven-core -> org.apache.maven.core</li>
     * <li>if artifactId starts with groupId then the artifactId is removed. eg.
     * org.apache:org.apache.maven.core -> org.apache.maven.core</li>
     * </ul>
     */
    public String getBundleSymbolicName(Artifact artifact) throws IOException {
        if ((artifact.getFile() != null) && artifact.getFile().isFile()) {
            Analyzer analyzer = new Analyzer();

            JarFile jar = null;
            try {
                jar = new JarFile(artifact.getFile(), false);

                if (jar.getManifest() != null) {
                    String symbolicNameAttribute = jar.getManifest().getMainAttributes()
                            .getValue(Analyzer.BUNDLE_SYMBOLICNAME);
                    Map bundleSymbolicNameHeader = analyzer.parseHeader(symbolicNameAttribute);

                    Iterator it = bundleSymbolicNameHeader.keySet().iterator();
                    if (it.hasNext()) {
                        return (String) it.next();
                    }
                }
            } catch (IOException e) {
                throw new IOException("Error reading manifest in jar "
                        + artifact.getFile().getAbsolutePath(), e);
            } finally {
                IOUtils.closeQuietly(jar);
            }
        }

        int i = artifact.getGroupId().lastIndexOf('.');
        if ((i < 0) && (artifact.getFile() != null) && artifact.getFile().isFile()) {
            String groupIdFromPackage = getGroupIdFromPackage(artifact.getFile());
            if (groupIdFromPackage != null) {
                return groupIdFromPackage;
            }
        }
        String lastSection = artifact.getGroupId().substring(++i);
        if (artifact.getArtifactId().equals(lastSection)) {
            return artifact.getGroupId();
        }
        if (artifact.getArtifactId().equals(artifact.getGroupId())
                || artifact.getArtifactId().startsWith(artifact.getGroupId() + ".")) {
            return artifact.getArtifactId();
        }
        if (artifact.getArtifactId().startsWith(lastSection)) {
            String artifactId = artifact.getArtifactId().substring(lastSection.length());
            if (Character.isLetterOrDigit(artifactId.charAt(0))) {
                return getBundleSymbolicName(artifact.getGroupId(), artifactId);
            } else {
                return getBundleSymbolicName(artifact.getGroupId(), artifactId.substring(1));
            }
        }
        return getBundleSymbolicName(artifact.getGroupId(), artifact.getArtifactId());
    }


    private String getGroupIdFromPackage(File artifactFile) {
        try {
            /* get package names from jar */
            Set packageNames = new HashSet();
            JarFile jar = new JarFile(artifactFile, false);
            Enumeration entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    File f = new File(entry.getName());
                    String packageName = f.getParent();
                    if (packageName != null) {
                        packageNames.add(packageName);
                    }
                }
            }
            jar.close();

            /* find the top package */
            String[] groupIdSections = null;
            for (Iterator it = packageNames.iterator(); it.hasNext(); ) {
                String packageName = (String) it.next();

                String[] packageNameSections = packageName.split("\\" + FILE_SEPARATOR);
                if (groupIdSections == null) {
                    /* first candidate */
                    groupIdSections = packageNameSections;
                } else
                // if ( packageNameSections.length < groupIdSections.length )
                {
                    /*
                     * find the common portion of current package and previous selected groupId
                     */
                    int i;
                    for (i = 0; (i < packageNameSections.length) && (i < groupIdSections.length); i++) {
                        if (!packageNameSections[i].equals(groupIdSections[i])) {
                            break;
                        }
                    }
                    groupIdSections = new String[i];
                    System.arraycopy(packageNameSections, 0, groupIdSections, 0, i);
                }
            }

            if ((groupIdSections == null) || (groupIdSections.length == 0)) {
                return null;
            }

            /* only one section as id doesn't seem enough, so ignore it */
            if (groupIdSections.length == 1) {
                return null;
            }

            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < groupIdSections.length; i++) {
                sb.append(groupIdSections[i]);
                if (i < groupIdSections.length - 1) {
                    sb.append('.');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            /* we took all the precautions to avoid this */
            throw new RuntimeException(e);
        }
    }


    public String getBundleFileName(Artifact artifact) throws IOException {
        return getBundleSymbolicName(artifact) + "_" + getVersion(artifact.getVersion()) + ".jar";
    }


    public String getVersion(Artifact artifact) {
        return getVersion(artifact.getVersion());
    }


    public String getVersion(String version) {
        return cleanupVersion(version);
    }

    /**
     * Clean up version parameters. Other builders use more fuzzy definitions of
     * the version syntax. This method cleans up such a version to match an OSGi
     * version.
     *
     * @param VERSION_STRING
     * @return
     */
    static final Pattern FUZZY_VERSION = Pattern.compile("(\\d+)(\\.(\\d+)(\\.(\\d+))?)?([^a-zA-Z0-9](.*))?",
            Pattern.DOTALL);


    static public String cleanupVersion(String version) {
        StringBuffer result = new StringBuffer();
        Matcher m = FUZZY_VERSION.matcher(version);
        if (m.matches()) {
            String major = m.group(1);
            String minor = m.group(3);
            String micro = m.group(5);
            String qualifier = m.group(7);

            if (major != null) {
                result.append(major);
                if (minor != null) {
                    result.append(".");
                    result.append(minor);
                    if (micro != null) {
                        result.append(".");
                        result.append(micro);
                        if (qualifier != null) {
                            result.append(".");
                            cleanupModifier(result, qualifier);
                        }
                    } else if (qualifier != null) {
                        result.append(".0.");
                        cleanupModifier(result, qualifier);
                    } else {
                        result.append(".0");
                    }
                } else if (qualifier != null) {
                    result.append(".0.0.");
                    cleanupModifier(result, qualifier);
                } else {
                    result.append(".0.0");
                }
            }
        } else {
            result.append("0.0.0.");
            cleanupModifier(result, version);
        }
        return result.toString();
    }


    static void cleanupModifier(StringBuffer result, String modifier) {
        for (int i = 0; i < modifier.length(); i++) {
            char c = modifier.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
                    || c == '-')
                result.append(c);
            else
                result.append('_');
        }
    }

}
