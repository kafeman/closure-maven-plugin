package com.google.closure.plugin.js;

import java.io.IOException;
import java.util.Collection;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.closure.plugin.common.CStyleLexer;
import com.google.closure.plugin.common.Sources.Source;
import com.google.closure.plugin.js.Identifier.GoogNamespace;
import com.google.closure.plugin.js.JsDepInfo.DepInfo;
import com.google.closure.plugin.plan.SourceMetadataMapBuilder;
import com.google.closure.plugin.plan.SourceMetadataMapBuilder.Extractor;
import com.google.closure.plugin.plan.BundlingPlanGraphNode;
import com.google.closure.plugin.plan.JoinNodes;
import com.google.closure.plugin.plan.Metadata;
import com.google.closure.plugin.plan.OptionPlanGraphNode.OptionsAndInputs;
import com.google.closure.plugin.plan.PlanContext;
import com.google.closure.plugin.plan.PlanGraphNode;
import com.google.common.io.ByteSource;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerInput;
import com.google.javascript.jscomp.SourceFile;

/**
 * Updates a mapping of JS sources to requires and provides while avoiding
 * parsing JS sources as much as possible.
 */
final class ComputeJsDepInfo
extends BundlingPlanGraphNode<JsOptions, JsDepInfo> {

  ComputeJsDepInfo(PlanContext context) {
    super(context);
  }

  @Override
  protected ImmutableList<JsDepInfo> bundlesFor(
      Optional<ImmutableList<JsDepInfo>> prevDepInfo,
      OptionsAndInputs<JsOptions> oi)
  throws IOException, MojoExecutionException {
    JsOptions options = oi.options;
    ImmutableList<Source> sources = oi.sources;

    ImmutableMap<Source, Metadata<DepInfo>> oldDepInfoMap;
    if (prevDepInfo.isPresent()) {
      ImmutableList<JsDepInfo> depInfos = prevDepInfo.get();
      Preconditions.checkState(depInfos.size() == 1);
      oldDepInfoMap = depInfos.get(0).depinfo;
    } else {
      oldDepInfoMap = ImmutableMap.<Source, Metadata<DepInfo>>of();
    }

    ImmutableMap<Source, Metadata<DepInfo>> newDepInfo;
    try {
      newDepInfo = computeDepInfo(
          context.log, oldDepInfoMap, options,
          SourceMetadataMapBuilder.REAL_FILE_LOADER,
          sources);
    } catch (IOException ex) {
      throw new MojoExecutionException("Failed to extract dependency info", ex);
    }

    return ImmutableList.of(new JsDepInfo(newDepInfo));
  }

  @VisibleForTesting
  static ImmutableMap<Source, Metadata<DepInfo>> computeDepInfo(
      Log log,
      ImmutableMap<Source, Metadata<DepInfo>> oldDepInfoMap,
      JsOptions options,
      Function<Source, ByteSource> loader,
      Iterable<? extends Source> sources)
  throws IOException {
    final Compiler parsingCompiler = new Compiler(
        new MavenLogJSErrorManager(log));
    parsingCompiler.initOptions(options.toCompilerOptions());

    return SourceMetadataMapBuilder.updateFromSources(
        oldDepInfoMap,
        loader,
        new Extractor<DepInfo>() {
          @Override
          public DepInfo extractMetadata(Source source, byte[] content)
          throws IOException {
            String code = new String(content, Charsets.UTF_8);

            SourceFile sourceFile = new SourceFile.Builder()
                .withCharset(Charsets.UTF_8)
                .withOriginalPath(source.relativePath.getPath())
                .buildFromCode(source.canonicalPath.getPath(), code);

            CompilerInput inp = new CompilerInput(sourceFile);
            inp.setCompiler(parsingCompiler);

            Collection<String> provides = inp.getProvides();
            Collection<String> requires = inp.getRequires();

            if (provides.isEmpty() && requires.isEmpty()) {
              // closure/goog/base.js provides basic definitions for things like
              // goog.require and goog.provide.
              // Anything that calls a goog.* method implicitly requires goog.

              // closure/goog/base.js gets around this by using the special
              // "@provideGoog" annotation.

              // That seems to be specially handled by JSCompiler but not via
              // the CompilerInput API.
              CStyleLexer lexer = new CStyleLexer(
                  sourceFile.getCode(),
                  true /* Need doc comments. */);
              for (CStyleLexer.Token headerToken : lexer) {
                if (headerToken.type != CStyleLexer.TokenType.DOC_COMMENT) {
                  break;
                }
                if (headerToken.containsText("@provideGoog")) {
                  provides = ImmutableSet.of("goog");
                  break;
                }
              }
            }

            return new DepInfo(
                inp.isModule(),
                inp.getName(),
                googNamespaces(provides),
                googNamespaces(requires));
          }
        },
        sources);
  }

  private static final Function<String, Identifier.GoogNamespace> TO_GOOG_NS =
      new Function<String, Identifier.GoogNamespace>() {

        @Override
        public GoogNamespace apply(String symbolText) {
          return new GoogNamespace(symbolText);
        }

      };

  static Iterable<Identifier.GoogNamespace> googNamespaces(
      Iterable<? extends String> symbols) {
    return Iterables.transform(symbols, TO_GOOG_NS);
  }

  @Override
  protected SV getStateVector() {
    return new SV(this);
  }

  static final class SV extends BundleStateVector<JsOptions, JsDepInfo> {

    private static final long serialVersionUID = 1L;

    SV(ComputeJsDepInfo node) {
      super(node);
    }

    @Override
    public PlanGraphNode<?> reconstitute(PlanContext c, JoinNodes joinNodes) {
      return apply(new ComputeJsDepInfo(c));
    }
  }
}

