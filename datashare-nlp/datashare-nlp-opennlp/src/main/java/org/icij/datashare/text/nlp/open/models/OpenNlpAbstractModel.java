package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.util.model.ArtifactProvider;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.icij.datashare.io.RemoteFiles;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.NlpStage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class OpenNlpAbstractModel {
    public static final String MODELS_OPENNLP_PATH = "dist/models/opennlp/1-5/";
    static final Path BASE_DIR = Paths.get(MODELS_OPENNLP_PATH);

    protected static final Object mutex = new Object();
    final Log LOGGER = LogFactory.getLog(getClass());

    final ConcurrentHashMap<Language, Lock> modelLock = new ConcurrentHashMap<Language, Lock>() {{
        for (Language l : Language.values()) {
            put(l, new ReentrantLock());
        }
    }};
    final NlpStage stage;

    OpenNlpAbstractModel(NlpStage stage) { this.stage = stage;}

    abstract ArtifactProvider getModel(Language language);
    abstract void putModel(Language language, InputStream content) throws IOException;
    abstract String getModelPath(Language language);

    public Optional<ArtifactProvider> get(Language language, ClassLoader loader) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            if (!load(language, loader))
                return Optional.empty();
            return Optional.of(getModel(language));
        } finally {
            l.unlock();
        }
    }

    boolean load(Language language, ClassLoader loader) {
        if (getModel(language) != null)
            return true;

        if (!isDownloaded(language, loader)) {
            download(language);
        }

        LOGGER.info("LOADING " + stage + " model for " + language);
        try (InputStream modelIS = loader.getResourceAsStream(getModelPath(language))) {
            putModel(language, modelIS);
        } catch (IOException e) {
            LOGGER.error("FAILED LOADING " + stage, e);
            return false;
        }
        LOGGER.info("LOADED " + stage + " model for " + language);
        return true;
    }

    boolean isDownloaded(Language language, ClassLoader loader) {
        return loader.getResource(getModelPath(language)) != null;
    }

    boolean download(Language language) {
        LOGGER.info("DOWNLOADING " + stage + " model for " + language);
        try {
            getRemoteFiles().download(MODELS_OPENNLP_PATH + language.iso6391Code(),
                    Paths.get(".").toAbsolutePath().normalize().toFile());
            LOGGER.info("DOWNLOADED " + stage + " model for " + language);
            return true;
        } catch (InterruptedException | IOException e) {
            LOGGER.error("FAILED DOWNLOADING " + stage, e);
            return false;
        }
    }

    RemoteFiles getRemoteFiles() {
        return RemoteFiles.getDefault();
    }
}
