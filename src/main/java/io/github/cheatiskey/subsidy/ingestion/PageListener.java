package io.github.cheatiskey.subsidy.ingestion;

@FunctionalInterface
public interface PageListener {

    void onPage(CollectedPage page);
}
