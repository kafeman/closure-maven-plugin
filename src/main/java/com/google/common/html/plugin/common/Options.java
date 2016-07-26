package com.google.common.html.plugin.common;

import java.io.Serializable;

import org.apache.maven.plugins.annotations.Parameter;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.html.plugin.plan.PlanKey;

/**
 * Options for a compiler.
 */
@SuppressWarnings("serial")  // is abstract
public abstract class Options implements Cloneable, Serializable {

  /**
   * An ID that must be unique among a bundle of options of the same kind used
   * in a compilation batch.
   * <p>
   * May be null if this has not been disambiguated as per
   * {@link OptionsUtils#disambiguateIds}.
   */
  @Parameter
  String id;

  boolean wasIdImplied;

  /**
   * An ID that must be unique among a bundle of options of the same kind used
   * in a compilation batch.
   * <p>
   * May be null if this has not been disambiguated as per
   * {@link OptionsUtils#disambiguateIds}.
   */
  public final String getId() {
    return Preconditions.checkNotNull(id);
  }

  /**
   * True if the ID was set automatically to avoid ambiguity.
   */
  public boolean wasIdImplied() {
    return this.wasIdImplied;
  }

  /**
   * Called after plexus configurations to create defaults for fields that were
   * not supplied by the plexus configurator.
   */
  protected abstract void createLazyDefaults();

  @SuppressWarnings("static-method")
  protected ImmutableList<? extends Options> getSubOptions() {
    return ImmutableList.of();
  }

  /**
   * May be overridden to store the asploded version of {@link #getSubOptions}.
   */
  protected void setSubOptions(
      @SuppressWarnings("unused")
      ImmutableList<? extends Options> preparedSubOptions) {
    throw new UnsupportedOperationException();
  }

  /**
   * A key ingredient that must not overlap with options of a different kind.
   */
  public final PlanKey getKey() {
    return PlanKey.builder("opt")
        .addString(getClass().getName())
        .addString(getId())
        .build();
  }

  /**
   * A best-effort copy since options have public immutable fields so that the
   * plexus configurator can muck with them.
   */
  @Override
  public Options clone() throws CloneNotSupportedException {
    return (Options) super.clone();
  }
}
