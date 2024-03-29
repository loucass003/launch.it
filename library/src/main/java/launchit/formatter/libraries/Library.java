package launchit.formatter.libraries;

import launchit.Launchit;
import launchit.formatter.rules.ExtractRules;
import launchit.formatter.rules.Rule;
import launchit.utils.OperatingSystem;

import java.io.File;
import java.util.List;
import java.util.Map;

public class Library {

    private String name;
    private LibraryDownloads downloads;
    private Map<OperatingSystem, String> natives;
    private ExtractRules extract;
    private List<Rule> rules;

    public String getName() {
        return name;
    }

    public LibraryDownloads getDownloads() {
        return downloads;
    }

    public Map<OperatingSystem, String> getNatives() {
        return natives;
    }

    public Artifact getNative(OperatingSystem type) {
        if (getNatives() == null || !getNatives().containsKey(type))
            return null;
        String name = getNatives().get(type);
        name = name.replace("${arch}", OperatingSystem.getArch());
        return getDownloads().getClassifiers().get(name);
    }

    public boolean matchEnvironement() {
        if (this.rules == null)
            return true;
        Rule.Action last = Rule.Action.DISALLOW;
        for (Rule rule : this.rules) {
            Rule.Action action = rule.getAction();
            if (action == null)
                continue;
            last = action;
        }
        return last == Rule.Action.ALLOW;
    }

    public ExtractRules getExtract() {
        return extract;
    }

    //******************************************************************************************************************

    public Artifact getEnvironmentLibrary() {
        Artifact artifact = getDownloads().getArtifact();
        if (getNatives() != null)
            artifact = getNative(OperatingSystem.getCurrentPlatform());
        return artifact;
    }

    public File getLocalFile(Launchit d) {
        Artifact lib = getEnvironmentLibrary();
        if (lib == null)
            return null;
        return new File(Library.getLibrariesFolder(d), lib.getPath());
    }

    public String getRemoteSha1() {
        return getEnvironmentLibrary().getSha1();
    }

    public static File getLibrariesFolder(Launchit d) {
        return new File(d.getConfig().getInstallFolder(), "libraries");
    }
}
