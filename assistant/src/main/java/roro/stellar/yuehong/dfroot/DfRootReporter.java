package roro.stellar.yuehong.dfroot;

/** Receives line-oriented output from the DirtyFrag native chain. */
public interface DfRootReporter {
    void report(String message);
}
