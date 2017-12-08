package org.icij.datashare.text.nlp.open;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagger;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetector;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import opennlp.tools.util.model.ArtifactProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractNlpPipeline;
import org.icij.datashare.text.nlp.Annotation;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.open.models.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
import static java.util.stream.Collectors.toList;
import static opennlp.tools.util.Span.spansToStrings;
import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.*;


/**
 * {@link org.icij.datashare.text.nlp.NlpPipeline}
 * {@link org.icij.datashare.text.nlp.AbstractNlpPipeline}
 * {@link Type#OPEN}
 *
 * <a href="https://opennlp.apache.org/">Apache OpenNLP</a>
 * Models v1.5
 * <a href="http://opennlp.sourceforge.net/models-1.5/">English</a>,
 * <a href="http://opennlp.sourceforge.net/models-1.5/">Spanish</a> (English used for TOKEN and SENTENCE),
 * <a href="http://opennlp.sourceforge.net/models-1.5/">German</a>,
 * <a href="https://sites.google.com/site/nicolashernandez/resources/opennlp">French</a>  (English used for NER)
 *
 * Created by julien on 3/29/16.
 */
public final class OpenNlpPipeline extends AbstractNlpPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, NER)));
                put(SPANISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, NER)));
                put(FRENCH,  new HashSet<>(asList(SENTENCE, TOKEN, POS, NER)));
                put(GERMAN,  new HashSet<>(asList(SENTENCE, TOKEN, POS)));
            }};

    // Sentence annotators (split string into sentences)
    private Map<Language, SentenceDetector> sentencer;

    // Token annotators (split string into tokens)
    private Map<Language, Tokenizer> tokenizer;

    // Part-of-Speech annotators (associate pos with tokens)
    private Map<Language, POSTagger> posTagger;

    // Named Entity Recognition annotators (associate entity category with tokens)
    Map<Language, List<NameFinderME>> nerFinder;

    // Annotator loading functions (per NlpStage)
    private final Map<NlpStage, BiFunction<ClassLoader, Language, Boolean>> annotatorLoader;


    public OpenNlpPipeline(final Properties properties) {
        super(properties);

        // SENTENCE <-- TOKEN <-- {POS, NER}
        stageDependencies.get(TOKEN).add(SENTENCE);
        stageDependencies.get(POS)  .add(TOKEN);
        stageDependencies.get(NER)  .add(TOKEN);

        annotatorLoader = new HashMap<NlpStage, BiFunction<ClassLoader, Language, Boolean>>(){{
            put(TOKEN,    OpenNlpPipeline.this::loadTokenizer);
            put(SENTENCE, OpenNlpPipeline.this::loadSentenceDetector);
            put(POS,      OpenNlpPipeline.this::loadPosTagger);
            put(NER,      OpenNlpPipeline.this::loadNameFinder);
        }};

        sentencer = new HashMap<>();
        tokenizer = new HashMap<>();
        posTagger = new HashMap<>();
        nerFinder = new HashMap<>();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Language, Set<NlpStage>> supportedStages() { return SUPPORTED_STAGES; }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean initialize(Language language) {
        if ( ! super.initialize(language))
            return false;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        stages.forEach( stage -> annotatorLoader.get(stage).apply(classLoader, language)
        );
        return true;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected Optional<Annotation> process(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);
        String annotators = " - SENTENCING ~ TOKENIZING";
        if (targetStages.contains(POS))
            annotators += " ~ POS-TAGGING";
        if (targetStages.contains(NER))
            annotators += " ~ NAME-FINDING";
        LOGGER.info(getClass().getName() + annotators + " for " + language.toString());

        // Split input into sentences
        Span[] sentenceSpans = sentences(input, language);
        for (Span sentenceSpan : sentenceSpans) {
            // Feed annotation
            int sentenceOffsetBegin = sentenceSpan.getStart();
            int sentenceOffsetEnd   = sentenceSpan.getEnd();
            annotation.add(SENTENCE, sentenceOffsetBegin, sentenceOffsetEnd);

            // Tokenize sentence
            String sentence = sentenceSpan.getCoveredText(input).toString();
            Span[] sentenceTokenSpans = tokenize(sentence, language);
            String[] sentenceTokens  = spansToStrings(sentenceTokenSpans, sentence);

            // Pos-tag sentence
            String[] sentencePosTags = new String[0];
            if (targetStages.contains(POS)) {
                sentencePosTags = postag(sentenceTokens, language);
            }

            // Feed annotation with token and pos
            for (Span sentenceTokenSpan : sentenceTokenSpans) {
                int tokenOffsetBegin = sentenceOffsetBegin + sentenceTokenSpan.getStart();
                int tokenOffsetEnd   = sentenceOffsetBegin + sentenceTokenSpan.getEnd();
                annotation.add(TOKEN, tokenOffsetBegin, tokenOffsetEnd);
                if (targetStages.contains(POS)) {
                    String pos = spanToString(sentenceTokenSpan, sentencePosTags);
                    annotation.add(POS, tokenOffsetBegin, tokenOffsetEnd, pos);
                }
            }

            // NER on sentence
            if (targetStages.contains(NER)) {
                for (NameFinderME nameFinderME : nerFinder.get(language)) {
                    Span[] nerSpans = nameFinderME.find(sentenceTokens);

                    // Feed annotation with ne
                    for (Span nerSpan : nerSpans) {
                        int nerStart = sentenceOffsetBegin + sentenceTokenSpans[nerSpan.getStart()].getStart();
                        int nerEnd   = sentenceOffsetBegin + sentenceTokenSpans[nerSpan.getEnd()-1].getEnd();
                        // TODO is nameFinderME.toString() tells if it is EntityName.Type ?
                        annotation.add(NER, nerStart, nerEnd, nameFinderME.toString());
                    }
                }
            }
        }
        return Optional.of( annotation );
    }

    private String spanToString(Span span, String[] elements) {
        return String.join(" ", asList(copyOfRange(elements, span.getStart(), span.getEnd())));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void terminate(Language language) {
        super.terminate(language);

        if (nerFinder.containsKey(language)) {
            nerFinder.get(language).forEach(NameFinderME::clearAdaptiveData);
        }

        // (Don't) keep models in memory
        if ( ! caching) {
            sentencer.remove(language);
            tokenizer.remove(language);
            posTagger.remove(language);
            nerFinder.remove(language);
        }
    }

    /**
     * Load sentence splitter from model (language-specific)
     *
     * @param loader the ClassLoader used to load model resources
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadSentenceDetector(ClassLoader loader, Language language) {
        if (sentencer.containsKey(language))
            return true;
        Optional<ArtifactProvider> model = OpenNlpSentenceModel.getInstance().get(language, loader);
        if ( ! model.isPresent())
            return false;
        sentencer.put(language, new SentenceDetectorME((SentenceModel) model.get()));
        return true;
    }

    /**
     * Split input string into sentences
     *
     * @param input is the String to split
     * @return Detected sentences as an array of String
     */
    private Span[] sentences(String input, Language language) {
        if ( ! stages.contains(SENTENCE) || ! sentencer.containsKey(language) )
            return new Span[0];
        return sentencer.get(language).sentPosDetect(input);
    }


    /**
     * Load tokenizer from model (language-specific)
     *
     * @param loader the ClassLoader used to load model resources
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadTokenizer(ClassLoader loader, Language language) {
        if ( tokenizer.containsKey(language) )
            return true;
        Optional<ArtifactProvider> model = OpenNlpTokenModel.getInstance().get(language, loader);
        if ( ! model.isPresent())
            return false;
        tokenizer.put(language, new TokenizerME((TokenizerModel) model.get()));
        return true;
    }

    /**
     * Tokenize input string
     *
     * @param input is the String to tokenize
     * @return Detected token Spans (tokens boundaries)
     */
    private Span[] tokenize(String input, Language language) {
        if ( ! stages.contains(TOKEN) || ! tokenizer.containsKey(language) )
            return new Span[0];
        return tokenizer.get(language).tokenizePos(input);
    }

    /**
     * Load part-of-speech tagging model (language-specific)
     *
     * @param loader the ClassLoader used to load model resources
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadPosTagger(ClassLoader loader, Language language) {
        if ( posTagger.containsKey(language) )
            return true;
        Optional<ArtifactProvider> model = OpenNlpPosModel.getInstance().get(language, loader);
        if ( ! model.isPresent())
            return false;
        posTagger.put(language, new POSTaggerME((POSModel) model.get()));
        return true;
    }

    /**
     * Get part-of-speech from tokens
     *
     * @param tokens is the sequence of tokens to annotate
     * @return Detected PoS as an array of String
     */
    private String[] postag(String[] tokens, Language language) {
        if ( ! stages.contains(POS) || ! posTagger.containsKey(language))
            return new String[0];
        return posTagger.get(language).tag(tokens);
    }

    /**
     * Load named entity recognisers (for each category) from model (language-specific)
     *
     * @param loader the ClassLoader used to load model resources
     * @return true if successfully loaded; false otherwise
     */
    private boolean loadNameFinder(ClassLoader loader, Language language) {
        Optional<ArtifactProvider> optNerModels = OpenNlpNerModel.getInstance().get(language, loader);
        if (optNerModels.isPresent()) {
            OpenNlpCompositeModel nerModels = (OpenNlpCompositeModel) optNerModels.get();
            final Stream<NameFinderME> nameFinderMEStream =
                    nerModels.models.stream().map(m -> new NameFinderME((TokenNameFinderModel) m));
            nerFinder.put(language, nameFinderMEStream.collect(toList()));
            return true;
        } else {
            return false;
        }
    }

    @Override
    public Optional<String> getPosTagSet(Language language) {
        return Optional.of(OpenNlpPosModel.POS_TAGSET.get(language));
    }

}

