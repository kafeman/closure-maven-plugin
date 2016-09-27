package com.google.closure.plugin.css;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.plugin.logging.Log;

import com.google.common.base.Ascii;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.css.GssFunctionMapProvider;
import com.google.common.css.JobDescription;
import com.google.common.css.JobDescriptionBuilder;
import com.google.common.css.OutputRenamingMapFormat;
import com.google.common.css.SourceCode;
import com.google.common.css.SubstitutionMapProvider;
import com.google.common.css.Vendor;
import com.google.common.css.JobDescription.OptimizeStrategy;
import com.google.closure.plugin.common.Asplodable;
import com.google.closure.plugin.common.FileExt;
import com.google.closure.plugin.common.OptionsUtils;
import com.google.closure.plugin.common.SourceOptions;
import com.google.closure.plugin.common.Sources;
import com.google.closure.plugin.plan.PlanContext;
import com.google.common.io.Files;

/**
 * Options for processing
 * <a href="https://github.com/google/closure-stylesheets">Closure Stylesheets</a>.
 */
public final class CssOptions extends SourceOptions {

  private static final long serialVersionUID = 8205371531045169081L;

  /** Allows @defs and @mixins from one file to propagate to other files. */
  public Boolean allowDefPropagation = true;
  /** Specify a non-standard function to whitelist, like alpha() */
  public void setAllowedNonStandardFunctions(String x) {
    allowedNonStandardFunctions.add(x);
  }
  private final List<String> allowedNonStandardFunctions = Lists.newArrayList();
  /** Specify a non-standard property to whitelist, like -ms-foo */
  public void setAllowedUnrecognizedProperties(String x) {
    allowedUnrecognizedProperties.add(x);
  }
  private final List<String> allowedUnrecognizedProperties =
    Lists.newArrayList();
  /** Allow use of experimental <code>{@literal @keyframes}</code>. */
  public Boolean allowKeyframes;
  /**
   * Allow use of the vendor specific
   * <a href="https://developer.mozilla.org/en-US/docs/Web/CSS/@document"
   *  >{@literal @-moz-document}</a> rule.
   */
  public Boolean allowMozDocument;
  /**
   * Do not error out on references to constants
   * for which there is no definition.
   */
  public Boolean allowUndefinedConstants;
  /**
   * Whether to allow unknown function calls,
   * leaving them as is, instead of reporting an error.
   */
  public Boolean allowUnrecognizedFunctions;
  /**
   * Whether to allow unrecognized properties.
   */
  public Boolean allowUnrecognizedProperties;
  /**
   * JSON map from strings to integers that specify integer constants
   * to be used in for loops.
   */
  public String compileConstants;
  /** Copyright notice to prepend to the output. */
  public String copyrightNotice;
  /** Add a prefix to all renamed css class names. */
  public String cssRenamingPrefix;
  /**
   * Whether to merge/split rules and selectors to eliminate duplicate styles.
   */
  public Boolean eliminateDeadStyles = true;
  /** A list of CSS class names that shouldn't be renamed. */
  public void setExcludedClassesFromRenaming(String x) {
    excludedClassesFromRenaming.add(x);
  }
  private final List<String> excludedClassesFromRenaming = Lists.newArrayList();
  /**
   * The fully qualified class name of a map provider of custom
   * GSS functions to resolve.
   */
  public Class<? extends GssFunctionMapProvider> gssFunctionMapProvider;
  /**
   * This specifies the display orientation the input files were
   * written for. You can choose between: LTR, RTL. LTR is the default
   * and means that the input style sheets were designed for use with
   * left to right display User Agents. RTL sheets are designed for
   * use with right to left UAs. Currently, all input files must have
   * the same orientation, as there is no way to specify the
   * orientation on a per-file or per-library basis.
   */
  public JobDescription.InputOrientation inputOrientation;
  /** The kind of optimizations to perform. */
  public JobDescription.OptimizeStrategy optimize;
  /**
   * Whether to format the output with newlines and indents so that
   * it is more readable.
   */
  public JobDescription.OutputFormat outputFormat;
  /**
   * Specify this option to perform automatic right to left conversion
   * of the input. You can choose between: LTR, RTL,
   * NOCHANGE. NOCHANGE means the input will not be changed in any way
   * with respect to direction issues. LTR outputs a sheet suitable
   * for left to right display and RTL outputs a sheet suitable for
   * right to left display. If the input orientation is different than
   * the requested output orientation, 'left' and 'right' values in
   * direction sensitive style rules are flipped. If the input already
   * has the desired orientation, this option effectively does nothing
   * except for defining GSS_LTR and GSS_RTL, respectively. The input
   * is LTR by default and can be changed with the input_orientation
   * flag.
   * <p>
   * When specified multiple times, multiple outputs are specified and
   * the {orient} can be used in the output path template to put output
   * compiled with different orientations in different output files.
   */
  public void setOutputOrientation(JobDescription.OutputOrientation x) {
    outputOrientation.add(x);
  }
  @Asplodable
  private final List<JobDescription.OutputOrientation> outputOrientation
    = Lists.newArrayList();
  /** How to format the output from the CSS class renaming. */
  public OutputRenamingMapFormat outputRenamingMapFormat;
  /** Preserve comments from sources into pretty printed output css. */
  public Boolean preserveComments;
  /**
   * TODO: remove per https://github.com/google/closure-stylesheets/issues/96
   */
  public Boolean processDependencies = true;
  /**
   * Whether to replace property values with
   * equivalent, compact representations.
   */
  public Boolean simplifyCss = true;
  /**
   * The level to generate source maps. You could choose between
   * DEFAULT, which will generate source map only for selectors,
   * blocks, rules, variables and symbol mappings, and ALL, which
   * outputs mappings for all elements.
   */
  public JobDescription.SourceMapDetailLevel sourceMapLevel;
  /**
   * Whether to be silent on malformed {@literal @goog.*} rules that are
   * specially interpreted by CSS stylesheets.
   */
  public Boolean suppressDependencyCheck;
  /**
   * Whether the Bidi flipper class should swap text like "-left-" and
   * "-right-" in {@code url(...)}s.
   */
  public Boolean swapLeftRightInUrl;
  /**
   * Whether the Bidi flipper class should swap text like "-ltr-" and
   * "-rtl-" in {@code url(...)}s.
   */
  public Boolean swapLtrRtlInUrl;
  /**
   * Specifies the name of a true condition.
   * The condition name can be used in @if boolean expressions.  The
   * conditions are ignored if GSS extensions are not enabled.
   */
  public void setTrueConditionNames(String x) {
    trueConditionNames.add(x);
  }
  private final List<String> trueConditionNames = Lists.newArrayList();
  /**
   * TODO: remove per https://github.com/google/closure-stylesheets/issues/96
   */
  public Boolean useInternalBidiFlipper;
  /**
   * Creates browser-vendor-specific output by stripping all
   * proprietary browser-vendor properties from the output except for
   * those associated with this vendor.
   */
  public void setVendor(Vendor x) {
    vendor.add(x);
  }
  @Asplodable
  private final List<Vendor> vendor = Lists.newArrayList();

  /**
   * The output CSS filename. If empty, standard output will be
   * used. The output is always UTF-8 encoded.
   * Defaults to target/css/{reldir}/compiled{-basename}{-orient}.css
   */
  public String output;
  /**
   * The source map output.
   * Provides a mapping from the generated output to their original
   * source code location.
   * Defaults to target/css/{reldir}/source-map{-basename}{-orient}.json
   */
  public String sourceMapFile;

  JobDescription getJobDescription(
      Log log, Iterable<? extends Sources.Source> sources,
      SubstitutionMapProvider cssSubstitutionMapProvider)
  throws IOException {
    JobDescriptionBuilder jobDescriptionBuilder = new JobDescriptionBuilder();

    jobDescriptionBuilder.setOptimizeStrategy(OptimizeStrategy.SAFE);

    for (Sources.Source src : sources) {
      String fileContent;
      try {
        fileContent = Files.toString(src.canonicalPath, Charsets.UTF_8);
      } catch (IOException ex) {
        log.error("Failed to read " + src.canonicalPath);
        throw ex;
      }
      jobDescriptionBuilder.addInput(new SourceCode(
          src.relativePath.getPath(), fileContent));
    }

    if (wasSet(allowDefPropagation)) {
      jobDescriptionBuilder.setAllowDefPropagation(allowDefPropagation);
    }
    if (wasSet(allowedNonStandardFunctions)) {
      jobDescriptionBuilder.setAllowedNonStandardFunctions(
          ImmutableList.copyOf(allowedNonStandardFunctions));
    }
    if (wasSet(allowedUnrecognizedProperties)) {
      jobDescriptionBuilder.setAllowedUnrecognizedProperties(
          ImmutableList.copyOf(allowedUnrecognizedProperties));
    }
    if (wasSet(allowKeyframes)) {
      jobDescriptionBuilder.setAllowKeyframes(allowKeyframes);
    }
    if (wasSet(allowMozDocument)) {
      jobDescriptionBuilder.setAllowMozDocument(allowMozDocument);
    }
    if (wasSet(allowUndefinedConstants)) {
      jobDescriptionBuilder.setAllowUndefinedConstants(allowUndefinedConstants);
    }
    if (wasSet(allowUnrecognizedFunctions)) {
      jobDescriptionBuilder.setAllowUnrecognizedFunctions(
          allowUnrecognizedFunctions);
    }
    if (wasSet(allowUnrecognizedProperties)) {
      jobDescriptionBuilder.setAllowUnrecognizedProperties(
          allowUnrecognizedProperties);
    }
    if (wasSet(compileConstants)) {
      Optional<ImmutableMap<String, Object>> constants =
          OptionsUtils.keyValueMapFromJson(log, compileConstants);
      if (constants.isPresent()) {
        Optional<ImmutableMap<String, Integer>> constantsTyped =
            requireValuesHaveType(
                log, constants.get(), Integer.class, "compileConstants");
        if (constantsTyped.isPresent()) {
          jobDescriptionBuilder.setCompileConstants(constantsTyped.get());
        }
      }
    }
    if (wasSet(copyrightNotice)) {
      jobDescriptionBuilder.setCopyrightNotice(copyrightNotice);
    }
    if (wasSet(cssRenamingPrefix)) {
      jobDescriptionBuilder.setCssRenamingPrefix(cssRenamingPrefix);
    }
    jobDescriptionBuilder.setCssSubstitutionMapProvider(
        cssSubstitutionMapProvider);
    if (wasSet(eliminateDeadStyles)) {
      jobDescriptionBuilder.setEliminateDeadStyles(eliminateDeadStyles);
    }
    if (wasSet(excludedClassesFromRenaming)) {
      jobDescriptionBuilder.setExcludedClassesFromRenaming(
          ImmutableList.copyOf(excludedClassesFromRenaming));
    }
    if (wasSet(gssFunctionMapProvider)) {
      Optional<GssFunctionMapProvider> provider =
          OptionsUtils.createInstanceUsingDefaultConstructor(
              log, GssFunctionMapProvider.class, gssFunctionMapProvider);
      if (provider.isPresent()) {
        jobDescriptionBuilder.setGssFunctionMapProvider(provider.get());
      }
    }
    if (wasSet(inputOrientation)) {
      jobDescriptionBuilder.setInputOrientation(inputOrientation);
    }
    if (wasSet(optimize)) {
      jobDescriptionBuilder.setOptimizeStrategy(optimize);
    }
    if (wasSet(outputFormat)) {
      jobDescriptionBuilder.setOutputFormat(outputFormat);
    }
    if (wasSet(outputOrientation)) {
      jobDescriptionBuilder.setOutputOrientation(outputOrientation.get(0));
    }
    jobDescriptionBuilder.setOutputRenamingMapFormat(
        OutputRenamingMapFormat.JSON);
    if (wasSet(preserveComments)) {
      jobDescriptionBuilder.setPreserveComments(preserveComments);
    }
    if (wasSet(processDependencies)) {
      jobDescriptionBuilder.setProcessDependencies(processDependencies);
    }
    if (wasSet(simplifyCss)) {
      jobDescriptionBuilder.setSimplifyCss(simplifyCss);
    }
    if (wasSet(sourceMapLevel)) {
      jobDescriptionBuilder.setSourceMapLevel(sourceMapLevel);
    }
    if (wasSet(suppressDependencyCheck)) {
      jobDescriptionBuilder.setSuppressDependencyCheck(suppressDependencyCheck);
    }
    if (wasSet(swapLeftRightInUrl)) {
      jobDescriptionBuilder.setSwapLeftRightInUrl(swapLeftRightInUrl);
    }
    if (wasSet(swapLtrRtlInUrl)) {
      jobDescriptionBuilder.setSwapLtrRtlInUrl(swapLtrRtlInUrl);
    }
    if (wasSet(trueConditionNames)) {
      jobDescriptionBuilder.setTrueConditionNames(
          ImmutableList.copyOf(trueConditionNames));
    }
    if (wasSet(useInternalBidiFlipper)) {
      jobDescriptionBuilder.setUseInternalBidiFlipper(useInternalBidiFlipper);
    }
    if (wasSet(vendor)) {
      jobDescriptionBuilder.setVendor(vendor.get(0));
    }

    jobDescriptionBuilder.setCreateSourceMap(true);

    return jobDescriptionBuilder.getJobDescription();
  }


  static <K, T>
  Optional<ImmutableMap<K, T>> requireValuesHaveType(
      Log log, ImmutableMap<K, ?> m, Class<T> valueType, String desc) {
    for (Map.Entry<K, ?> e : m.entrySet()) {
      Object v = e.getValue();
      if (v != null && !valueType.isInstance(v)) {
        log.error("Value " + v + " in " + desc
            + " has type " + v.getClass().getSimpleName()
            + ", not " + valueType.getSimpleName());
        return Optional.absent();
      }
    }
    @SuppressWarnings("unchecked")
    ImmutableMap<K, T> typedMap = (ImmutableMap<K, T>) m;
    return Optional.of(typedMap);
  }

  /**
   * The outputs of a compilation job.
   */
  public static final class Outputs implements Serializable {
    private static final long serialVersionUID = -600433593903008098L;

    /** The file that will contain the process CSS. */
    public final File css;
    /** THe source map for that compilation step. */
    public final File sourceMap;

    /**
     * @param opts the options for the compilation job.
     * @param source the entry point source file.
     */
    public Outputs(
        PlanContext context,
        CssOptions opts,
        Sources.Source source) {

      String basename = FilenameUtils.removeExtension(
          source.relativePath.getName());
      String reldir = source.relativePath.getParent();
      @SuppressWarnings("synthetic-access")
      String orientation =
          opts.outputOrientation.size() == 1
          ? Ascii.toLowerCase(opts.outputOrientation.get(0).name()) : null;
          @SuppressWarnings("synthetic-access")
      String vendor =
          opts.vendor.size() == 1
          ? Ascii.toLowerCase(opts.vendor.get(0).name()) : null;
      PathTemplateSubstitutor ts;
      {
        ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
        if (basename != null) { b.put("basename", basename); }
        if (reldir != null) { b.put("reldir", reldir); }
        if (orientation != null) { b.put("orientation", orientation); }
        if (vendor != null) { b.put("vendor", vendor); }
        ts = new PathTemplateSubstitutor(b.build());
      }
      File cssOutputDir = context.closureOutputDirectoryForExt(FileExt.CSS);
      this.css = ts.substitute(cssOutputDir, opts.output);
      this.sourceMap = ts.substitute(cssOutputDir, opts.sourceMapFile);
    }

    /** All of the output files. */
    public ImmutableList<File> allOutputFiles() {
      return ImmutableList.of(css, sourceMap);
    }
  }

  @Override
  public CssOptions clone() {
    try {
      return (CssOptions) super.clone();
    } catch (CloneNotSupportedException ex) {
      throw (AssertionError) new AssertionError().initCause(ex);
    }
  }

  @Override
  protected void createLazyDefaults() {
    // Done
  }

  private static boolean wasSet(String parameterValue) {
    return parameterValue != null;
  }

  private static boolean wasSet(Class<?> parameterValue) {
    return parameterValue != null;
  }

  private static boolean wasSet(List<?> parameterValues) {
    return !parameterValues.isEmpty();
  }

  private static boolean wasSet(Boolean parameterValue) {
    return parameterValue != null;
  }

  private static boolean wasSet(Enum<?> parameterValue) {
    return parameterValue != null;
  }


  @Override
  protected ImmutableList<FileExt> sourceExtensions() {
    return ImmutableList.of(FileExt.CSS);
  }
}

final class PathTemplateSubstitutor {
  final ImmutableMap<String, String> substitutions;

  PathTemplateSubstitutor(
      Map<? extends String, ? extends String> substitutions) {
    this.substitutions = ImmutableMap.<String, String>copyOf(substitutions);
  }

  private static final Pattern INTERPOLATION =
      Pattern.compile("[{]([^\\w{}]*)(\\w+)([^\\w{}]*)[}]");

  File substitute(File outputDir, String templateText) {
    Matcher m = INTERPOLATION.matcher(templateText);
    StringBuilder sb = new StringBuilder();
    sb.append(outputDir.getPath()).append(File.separator);
    int written = 0;
    while (m.find()) {
      int start = m.start();
      sb.append(templateText, written, start);
      written = m.end();
      String prefix = m.group(1);
      String key = m.group(2);
      String suffix = m.group(3);
      if (substitutions.containsKey(key)) {
        sb.append(prefix)
           .append(substitutions.get(key))
           .append(suffix);
      }
    }
    sb.append(templateText, written, templateText.length());
    return new File(Files.simplifyPath(sb.toString()));
  }
}
