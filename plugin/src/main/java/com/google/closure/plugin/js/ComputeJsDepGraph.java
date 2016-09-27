package com.google.closure.plugin.js;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.closure.plugin.common.TopoSort;
import com.google.closure.plugin.common.Words;
import com.google.closure.plugin.js.Identifier.GoogNamespace;
import com.google.closure.plugin.js.Identifier.ModuleName;
import com.google.closure.plugin.js.JsDepInfo.DepInfo;
import com.google.closure.plugin.common.Sources.Source;
import com.google.closure.plugin.common.SourceFileProperty;
import com.google.closure.plugin.plan.Hash;
import com.google.closure.plugin.plan.JoinNodes;
import com.google.closure.plugin.plan.Metadata;
import com.google.closure.plugin.plan.PlanContext;
import com.google.closure.plugin.plan.PlanGraphNode;
import com.google.javascript.jscomp.Compiler;

final class ComputeJsDepGraph extends PlanGraphNode<ComputeJsDepGraph.SV> {
  final JsOptions options;
  final JsDepInfo depInfo;
  private Optional<Modules> modules = Optional.absent();

  public ComputeJsDepGraph(
      PlanContext context,
      JsOptions options,
      JsDepInfo depInfo) {
    super(context);
    this.options = options;
    this.depInfo = depInfo;
  }

  @Override
  protected void processInputs() throws IOException, MojoExecutionException {
    ImmutableSet<Source> sources = depInfo.depinfo.keySet();

    this.modules = Optional.of(computeDepGraph(
        context.log, options, sources, depInfo));
  }

  static Modules computeDepGraph(
      Log log, JsOptions options,
      Iterable<? extends Source> sources, JsDepInfo depInfo)
  throws MojoExecutionException {
    // Group sources into modules based on directory.
    ImmutableMap<ModuleName, ModuleInfo> moduleInfo =
        buildModuleInfoMap(sources, depInfo);

    // Look at the dep-info to figure out which sources are actually required.
    ImmutableMap<ModuleName, ImmutableList<SourceAndDepInfo>> sourcesPerModule;
    {
      // This algorithm is unnecessarily iterative.  It could be tightened up by
      // keeping a multimap from symbols to providers.
      Set<GoogNamespace> allRequired = Sets.newLinkedHashSet();
      for (ModuleInfo mi : moduleInfo.values()) {
        allRequired.addAll(mi.getRequired());
      }
      ImmutableSet<GoogNamespace> previouslyProvided = ImmutableSet.of();
      for (Set<GoogNamespace> allProvided = Sets.newLinkedHashSet();
          !allRequired.isEmpty();
          previouslyProvided = ImmutableSet.copyOf(allProvided)) {
        allProvided.clear();
        if (log.isDebugEnabled()) {
          log.debug(
              "JS bundle " + options.getId() + " still requires "
              + GoogNamespace.shortLogForm(allRequired));
        }

        for (ModuleInfo mi : moduleInfo.values()) {
          mi.prettyPleaseProvide(allRequired);
          allProvided.addAll(mi.getProvides());
        }

        if (log.isDebugEnabled()) {
          log.debug(
              "JS bundle " + options.getId() + " provides "
              + GoogNamespace.shortLogForm(allProvided));
        }

        // Avoid inf recursion when there are unsatisfied requirements by
        // checking monotonicity and let later stages diagnose the problem.
        if (allProvided.size() == previouslyProvided.size()) {
          break;
        }
      }

      ImmutableMap.Builder<ModuleName, ImmutableList<SourceAndDepInfo>> b =
          ImmutableMap.builder();
      for (Map.Entry<ModuleName, ModuleInfo> e : moduleInfo.entrySet()) {
        ImmutableList<SourceAndDepInfo> moduleSources =
            ImmutableList.copyOf(e.getValue().getUsedSources());
        if (!moduleSources.isEmpty()) {
          b.put(e.getKey(), moduleSources);
        }
      }
      sourcesPerModule = b.build();
    }
    if (log.isDebugEnabled()) {
      StringBuilder sb = new StringBuilder(
          "JS bundle " + options.getId() + " modules");
      for (Map.Entry<ModuleName, ImmutableList<SourceAndDepInfo>> e
           : sourcesPerModule.entrySet()) {
        sb.append("\n\t").append(e.getKey().text);
        String sep = " : ";
        for (SourceAndDepInfo sdi : e.getValue()) {
          sb.append(sep);
          sep = ", ";
          sb.append(sdi.s.relativePath.getName());
        }
      }
      log.debug(sb);
    }

    final Compiler parsingCompiler = new Compiler(
        new MavenLogJSErrorManager(log));
    parsingCompiler.initOptions(options.toCompilerOptions());

    TopoSort<ModuleName, GoogNamespace> moduleTopoSort;
    {
      Map<ModuleName, Set<GoogNamespace>> allRequires = Maps.newLinkedHashMap();
      Map<ModuleName, Set<GoogNamespace>> allProvides = Maps.newLinkedHashMap();
      for (Map.Entry<ModuleName, ImmutableList<SourceAndDepInfo>> e
           : sourcesPerModule.entrySet()) {
       ModuleName moduleName = e.getKey();

       Set<GoogNamespace> reqs = Sets.newTreeSet();
       ImmutableSet.Builder<GoogNamespace> provsBuilder =
           ImmutableSet.builder();
       for (SourceAndDepInfo sdi : e.getValue()) {
         reqs.addAll(sdi.di.requires);
         provsBuilder.addAll(sdi.di.provides);
       }
       // We don't need to require internally provided symbols.
       ImmutableSet<GoogNamespace> provs = provsBuilder.build();
       reqs.removeAll(provs);

       allRequires.put(moduleName, ImmutableSet.copyOf(reqs));
       allProvides.put(moduleName, provs);
      }
      try {
        moduleTopoSort = new TopoSort<>(
            Functions.forMap(allRequires),
            Functions.forMap(allProvides),
            sourcesPerModule.keySet());
      } catch (TopoSort.CyclicRequirementException ex) {
        throw new MojoExecutionException("Mismatched require/provides", ex);
      } catch (TopoSort.MissingRequirementException ex) {
        throw new MojoExecutionException("Mismatched require/provides", ex);
      }
    }
    ImmutableList<ModuleName> moduleOrder = moduleTopoSort.getSortedItems();

    // When computing the internal ordering, we don't need to require things
    // that are provided by dependencies and which are not provided internally.
    // Since we've already figured out the dependency order, we just keep a
    // running total of symbols to subtract from the current module's required
    // set.
    final Set<GoogNamespace> providedByPossibleDependencies = Sets.newTreeSet();

    ImmutableList.Builder<Modules.Module> moduleList = ImmutableList.builder();
    for (ModuleName moduleName : moduleOrder) {
      ImmutableList<ModuleName> orderedDeps =
          moduleTopoSort.getDependenciesTransitive(moduleName);

      final Map<String, SourceAndDepInfo> inputsByCiName =
          Maps.newLinkedHashMap();
      ImmutableList<SourceAndDepInfo> moduleSources =
          sourcesPerModule.get(moduleName);
      for (SourceAndDepInfo sdi : moduleSources) {
        String ciName = sdi.di.closureCompilerInputName;
        Preconditions.checkState(
            null == inputsByCiName.put(ciName, sdi));
      }

      TopoSort<SourceAndDepInfo, GoogNamespace> sourcesTopoSort;
      try {
        sourcesTopoSort = new TopoSort<>(
            new Function<SourceAndDepInfo, Collection<GoogNamespace>>() {
              @Override
              public Collection<GoogNamespace> apply(SourceAndDepInfo sdi) {
                ImmutableSet<GoogNamespace> reqs = sdi.di.requires;
                ImmutableList.Builder<GoogNamespace> reqsFiltered =
                    ImmutableList.builder();
                for (GoogNamespace req : reqs) {
                  if (!providedByPossibleDependencies.contains(req)) {
                    reqsFiltered.add(req);
                  }
                }
                return reqsFiltered.build();
              }
            },
            new Function<SourceAndDepInfo, Collection<GoogNamespace>>() {
              @Override
              public Collection<GoogNamespace> apply(SourceAndDepInfo sdi) {
                return sdi.di.provides;
              }
            },
            inputsByCiName.values());
      } catch (TopoSort.CyclicRequirementException ex) {
        throw new MojoExecutionException(
            "Mismatched require/provides in module " + moduleName, ex);
      } catch (TopoSort.MissingRequirementException ex) {
        throw new MojoExecutionException(
            "Mismatched require/provides in module " + moduleName, ex);
      }

      ImmutableList.Builder<Source> orderedSources = ImmutableList.builder();
      for (SourceAndDepInfo sdi : sourcesTopoSort.getSortedItems()) {
        orderedSources.add(sdi.s);
        providedByPossibleDependencies.addAll(sdi.di.provides);
      }

      // Build a Module with the inputs in topo-order.
      Modules.Module module = new Modules.Module(
          moduleName.text,
          ImmutableList.copyOf(
              Lists.transform(orderedDeps, Identifier.GET_TEXT)),
          orderedSources.build());
      moduleList.add(module);
    }

    return new Modules(moduleList.build());
  }

  private static
  ImmutableMap<ModuleName, ModuleInfo> buildModuleInfoMap(
      Iterable<? extends Source> sources, JsDepInfo depInfo) {
    Multimap<ModuleName, SourceAndDepInfo> sourcesPerModule =
        Multimaps.newMultimap(
            Maps.<ModuleName, Collection<SourceAndDepInfo>>newTreeMap(),
            new Supplier<Collection<SourceAndDepInfo>>() {
              @Override
              public Collection<SourceAndDepInfo> get() {
                return Sets.newTreeSet(
                    new Comparator<SourceAndDepInfo>() {
                      @Override
                      public int compare(
                          SourceAndDepInfo a, SourceAndDepInfo b) {
                        return a.s.relativePath.compareTo(b.s.relativePath);
                      }
                    });
              }
            });

    collectSourceFiles(sources, sourcesPerModule, depInfo);

    ImmutableMap.Builder<ModuleName, ModuleInfo> b = ImmutableMap.builder();
    for (Map.Entry<ModuleName, ? extends Iterable<SourceAndDepInfo>> e
        : sourcesPerModule.asMap().entrySet()) {
      ModuleName name = e.getKey();
      b.put(name, new ModuleInfo(name, e.getValue()));
    }
    return b.build();
  }


  private static void collectSourceFiles(
      Iterable<? extends Source> sources,
      Multimap<ModuleName, SourceAndDepInfo> sourceFilesByModuleName,
      JsDepInfo depInfo) {
    for (Source s : sources) {
      Metadata<DepInfo> mdi = depInfo.depinfo.get(s);
      if (mdi == null) {
        throw new IllegalArgumentException(
            "Missing dependency info for " + s);
      }
      DepInfo di = mdi.metadata;

      ModuleName moduleName;
      if (di.isModule) {
        moduleName = new ModuleName(di.provides.iterator().next().text);
      } else {
        String extensionlessRelativePath =
            FilenameUtils.removeExtension(s.relativePath.getPath());
        if (Words.endsWithWordOrIs(extensionlessRelativePath, "main")
            || Words.endsWithWordOrIs(extensionlessRelativePath, "test")) {
          moduleName = new ModuleName(
              extensionlessRelativePath.replaceAll("[/\\\\]", "."));
        } else {
          moduleName = s.root.ps.contains(SourceFileProperty.TEST_ONLY)
              ? ModuleName.DEFAULT_TEST_MODULE_NAME
              : ModuleName.DEFAULT_MAIN_MODULE_NAME;
        }
      }

      sourceFilesByModuleName.put(
          moduleName,
          new SourceAndDepInfo(s, di));
    }
  }

  static final class SourceAndDepInfo
  implements Comparable<SourceAndDepInfo> {
    final Source s;
    final DepInfo di;

    SourceAndDepInfo(Source s, DepInfo di) {
      this.s = s;
      this.di = di;
    }

    @Override
    public int compareTo(SourceAndDepInfo that) {
      return this.s.canonicalPath.compareTo(that.s.canonicalPath);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof SourceAndDepInfo)) { return false; }
      SourceAndDepInfo that = (SourceAndDepInfo) o;
      return this.s.canonicalPath.equals(that.s.canonicalPath);
    }

    @Override
    public int hashCode() {
      return this.s.canonicalPath.hashCode();
    }

    @Override
    public String toString() {
      return "{Source " + s.canonicalPath + "}";
    }

    boolean isOptional() {
      return s.root.ps.contains(SourceFileProperty.LOAD_AS_NEEDED);
    }
  }

  static final class ModuleInfo {
    final ModuleName name;
    final ImmutableList<SourceAndDepInfo> sources;
    /** The set of symbols that this module has committed to providing. */
    private final Set<GoogNamespace> provides = Sets.newLinkedHashSet();
    /** The set of symbols that this module could provide as needed. */
    private final Set<GoogNamespace> canProvide = Sets.newLinkedHashSet();
    /** The set of symbols that this module requires. */
    private final Set<GoogNamespace> requires = Sets.newLinkedHashSet();
    /**
     * The set of symbols that would be newly required were the module to
     * commit to providing the key.
     */
    private final Multimap<GoogNamespace, GoogNamespace> implies =
        HashMultimap.create();

    ModuleInfo(ModuleName name, Iterable<? extends SourceAndDepInfo> sources) {
      this.name = name;
      this.sources = ImmutableList.copyOf(sources);
      for (SourceAndDepInfo sdi : this.sources) {
        if (sdi.isOptional()) {
          canProvide.addAll(sdi.di.provides);
          for (GoogNamespace p : sdi.di.provides) {
            implies.putAll(p, sdi.di.requires);
          }
        } else {
          provides.addAll(sdi.di.provides);
          requires.addAll(sdi.di.requires);
        }
      }
      this.requires.removeAll(this.provides);
    }

    /**
     * Makes a best effort to commit to providing the given symbols which
     * may then require other symbols.
     *
     * @param reqs the set of symbols that the caller would like this module
     *    to provide.  Modified in place to remove those that were actually
     *    provided, and to require anything that must be provided in order
     *    for this module to provide symbols that were newly committed to.
     */
    void prettyPleaseProvide(Set<GoogNamespace> reqs) {
      Set<GoogNamespace> newlyRequired = Sets.newLinkedHashSet();
      Set<GoogNamespace> added = Sets.newLinkedHashSet();

      for (GoogNamespace req : reqs) {
        if (!canProvide.contains(req)) {
          continue;
        }
        added.add(req);
        newlyRequired.addAll(implies.removeAll(req));
      }

      // Advertise that we provide the things we've committed to providing.
      provides.addAll(added);
      canProvide.removeAll(added);

      // Advertise that we no longer require things that are provided
      // internally.
      newlyRequired.removeAll(added);
      requires.addAll(newlyRequired);

      // Update the input o that which is required after this call.
      reqs.addAll(newlyRequired);
      reqs.removeAll(provides);
    }

    Set<GoogNamespace> getRequired() {
      return Collections.unmodifiableSet(requires);
    }

    Set<GoogNamespace> getProvides() {
      return Collections.unmodifiableSet(provides);
    }

    Iterable<SourceAndDepInfo> getUsedSources() {
      return Iterables.filter(
          sources,
          new Predicate<SourceAndDepInfo>() {

            @SuppressWarnings("synthetic-access")
            @Override
            public boolean apply(SourceAndDepInfo sdi) {
              // Required sources need not export any symbols.
              return !sdi.isOptional()
                  || !Collections.disjoint(
                      sdi.di.provides, ModuleInfo.this.provides);
            }
          });
    }
  }

  static final class SV implements PlanGraphNode.StateVector {
    private static final long serialVersionUID = 1L;

    final JsOptions options;
    final JsDepInfo depInfo;
    final Modules modules;

    SV(JsOptions options, JsDepInfo depInfo, Modules modules) {
      this.options = options;
      this.depInfo = depInfo;
      this.modules = modules;
    }

    @SuppressWarnings("synthetic-access")
    @Override
    public PlanGraphNode<?> reconstitute(PlanContext c, JoinNodes joinNodes) {
      ComputeJsDepGraph n = new ComputeJsDepGraph(c, options, depInfo);
      n.modules = Optional.of(modules);
      return n;
    }
  }

  @Override
  protected Optional<ImmutableList<CompileJs>> rebuildFollowersList(
      JoinNodes joinNodes)
  throws MojoExecutionException {
    ImmutableList<PlanGraphNode<?>> oldFollowers = this.getFollowerList();
    if (oldFollowers.size() == 1) {
      PlanGraphNode<?> f0 = oldFollowers.get(0);
      if (f0 instanceof CompileJs) {
        CompileJs oldCompileJs = (CompileJs) f0;
        if (Hash.same(options, oldCompileJs.options)
            && Hash.same(modules.get(), oldCompileJs.bundle)) {
          return Optional.absent();
        }
      }
    }

    return Optional.of(ImmutableList.of(
        new CompileJs(context, options, modules.get())));
  }

  @Override
  protected void markOutputs() {
    // Done
  }

  @Override
  protected SV getStateVector() {
    return new SV(options, depInfo, modules.get());
  }

  @Override
  protected boolean hasChangedInputs() throws IOException {
    return !modules.isPresent();
  }
}
