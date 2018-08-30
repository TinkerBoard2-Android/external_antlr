/**
[The "BSD licence"]

ANTLR        - Copyright (c) 2005-2008 Terence Parr
Maven Plugin - Copyright (c) 2009      Jim Idle

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
3. The name of the author may not be used to endorse or promote products
derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/* ========================================================================
 * This is the definitive ANTLR3 Mojo set. All other sets are belong to us.
 */
package org.antlr.mojo.antlr3;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.antlr.Tool;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;

/**
 * Parses ANTLR grammar files {@code *.g} and transforms them into Java source
 * files.
 *
 * @goal antlr
 * @phase generate-sources
 * @requiresDependencyResolution compile
 * @requiresProject true
 * 
 * @author <a href="mailto:jimi@temporal-wave.com">Jim Idle</a>
 */
public class Antlr3Mojo
        extends AbstractMojo {

    // First, let's deal with the options that the ANTLR tool itself
    // can be configured by.
    //
    /**
     * If set to true, then after the tool has processed an input grammar file
     * it will report various statistics about the parser, such as information
     * on cyclic DFAs, which rules may use backtracking, and so on.
     *
     * @parameter default-value="false"
     */
    protected boolean report;
    /**
     * If set to true, then the ANTLR tool will print a version of the input
     * grammar(s) which are stripped of any embedded actions.
     *
     * @parameter default-value="false"
     */
    protected boolean printGrammar;
    /**
     * If set to true, then the code generated by the ANTLR code generator will
     * be set to debug mode. This means that when run, the code will 'hang' and
     * wait for a debug connection on a TCP port (49100 by default).
     *
     * @parameter default-value="false"
     */
    protected boolean debug;
    /**
     * If set to true, then the generated parser will compute and report profile
     * information at runtime.
     *
     * @parameter default-value="false"
     */
    protected boolean profile;
    /**
     * If set to true, then the ANTLR tool will generate a description of the
     * NFA for each rule in <a href="http://www.graphviz.org">Dot format</a>
     *
     * @parameter default-value="false"
     */
    protected boolean nfa;
    /**
     * If set to true, then the ANTLR tool will generate a description of the
     * DFA for each decision in the grammar in
     * <a href="http://www.graphviz.org">Dot format</a>.
     *
     * @parameter default-value="false"
     */
    protected boolean dfa;
    /**
     * If set to true, the generated parser code will log rule entry and exit
     * points to stdout ({@link System#out} for the Java target) as an aid to
     * debugging.
     *
     * @parameter default-value="false"
     */
    protected boolean trace;
    /**
     * If this parameter is set, it indicates that any warning or error messages
     * returned by ANLTR, should be formatted in the specified way. Currently,
     * ANTLR supports the built-in formats {@code antlr}, {@code gnu} and
     * {@code vs2005}.
     *
     * @parameter default-value="antlr"
     */
    protected String messageFormat;
    /**
     * If set to true, then ANTLR will report verbose messages during the code
     * generation process. This includes the names of files, the version of
     * ANTLR, and more.
     *
     * @parameter default-value="true"
     */
    protected boolean verbose;

    /**
     * The maximum number of alternatives allowed in an inline switch statement.
     * Beyond this, ANTLR will not generate a switch statement for the DFA.
     *
     * @parameter default-value="300"
     */
    private int maxSwitchCaseLabels;

    /**
     * The minimum number of alternatives for ANTLR to generate a switch
     * statement. For decisions with fewer alternatives, an if/else if/else
     * statement will be used instead.
     *
     * @parameter default-value="3"
     */
    private int minSwitchAlts;

    /* --------------------------------------------------------------------
     * The following are Maven specific parameters, rather than specific
     * options that the ANTLR tool can use.
     */

    /**
     * Provides an explicit list of all the grammars that should be included in
     * the generate phase of the plugin. Note that the plugin is smart enough to
     * realize that imported grammars should be included but not acted upon
     * directly by the ANTLR Tool.
     * <p>
     * A set of Ant-like inclusion patterns used to select files from the source
     * directory for processing. By default, the pattern <code>**&#47;*.g</code>
     * is used to select grammar files.</p>
     *
     * @parameter
     */
    protected Set<String> includes = new HashSet<String>();
    /**
     * A set of Ant-like exclusion patterns used to prevent certain files from
     * being processed. By default, this set is empty such that no files are
     * excluded.
     *
     * @parameter
     */
    protected Set<String> excludes = new HashSet<String>();
    /**
     * The current Maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;
    /**
     * The directory where the ANTLR grammar files ({@code *.g}) are located.
     *
     * @parameter default-value="${basedir}/src/main/antlr3"
     */
    private File sourceDirectory;
    /**
     * The directory where the parser files generated by ANTLR will be stored.
     * The directory will be registered as a compile source root of the project
     * such that the generated files will participate in later build phases like
     * compiling and packaging.
     *
     * @parameter default-value="${project.build.directory}/generated-sources/antlr3"
     * @required
     */
    private File outputDirectory;
    /**
     * Location for imported token files, e.g. {@code *.tokens} and imported
     * grammars. Note that ANTLR will not try to process grammars that it finds
     * to be imported into other grammars (in the same processing session).
     *
     * @parameter default-value="${basedir}/src/main/antlr3/imports"
     */
    private File libDirectory;

    public File getSourceDirectory() {
        return sourceDirectory;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public File getLibDirectory() {
        return libDirectory;
    }

    void addSourceRoot(File outputDir) {
        project.addCompileSourceRoot(outputDir.getPath());
    }
    /**
     * An instance of the ANTLR tool build.
     */
    protected Tool tool;

    /**
     * The main entry point for this Mojo, it is responsible for converting
     * ANTLR 3.x grammars into the target language specified by the grammar.
     *
     * @throws MojoExecutionException if a configuration or grammar error causes
     * the code generation process to fail
     * @throws MojoFailureException if an instance of the ANTLR 3 {@link Tool}
     * cannot be created
     */
    public void execute()
            throws MojoExecutionException, MojoFailureException {

        Log log = getLog();

        // Check to see if the user asked for debug information, then dump all the
        // parameters we have picked up if they did.
        //
        if (log.isDebugEnabled()) {

            // Excludes
            //
            for (String e : excludes) {
                log.debug("ANTLR: Exclude: " + e);
            }

            // Includes
            //
            for (String e : includes) {
                log.debug("ANTLR: Include: " + e);
            }

            // Output location
            //
            log.debug("ANTLR: Output: " + outputDirectory);

            // Library directory
            //
            log.debug("ANTLR: Library: " + libDirectory);

            // Flags
            //
            log.debug("ANTLR: report              : " + report);
            log.debug("ANTLR: printGrammar        : " + printGrammar);
            log.debug("ANTLR: debug               : " + debug);
            log.debug("ANTLR: profile             : " + profile);
            log.debug("ANTLR: nfa                 : " + nfa);
            log.debug("ANTLR: dfa                 : " + dfa);
            log.debug("ANTLR: trace               : " + trace);
            log.debug("ANTLR: messageFormat       : " + messageFormat);
            log.debug("ANTLR: maxSwitchCaseLabels : " + maxSwitchCaseLabels);
            log.debug("ANTLR: minSwitchAlts       : " + minSwitchAlts);
            log.debug("ANTLR: verbose             : " + verbose);
        }

        // Ensure that the output directory path is all in tact so that
        // ANTLR can just write into it.
        //
        File outputDir = getOutputDirectory();

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // First thing we need is an instance of the ANTLR 3.1 build tool
        //
        try {
            // ANTLR Tool buld interface
            //
            tool = new Tool();
        } catch (Exception e) {
            log.error("The attempt to create the ANTLR build tool failed, see exception report for details");

            throw new MojoFailureException("Jim failed you!");
        }

        // Next we need to set the options given to us in the pom into the
        // tool instance we have created.
        //
        tool.setDebug(debug);
        tool.setGenerate_DFA_dot(dfa);
        tool.setGenerate_NFA_dot(nfa);
        tool.setProfile(profile);
        tool.setReport(report);
        tool.setPrintGrammar(printGrammar);
        tool.setTrace(trace);
        tool.setVerbose(verbose);
        tool.setMessageFormat(messageFormat);
        tool.setMaxSwitchCaseLabels(maxSwitchCaseLabels);
        tool.setMinSwitchAlts(minSwitchAlts);

        // Where do we want ANTLR to produce its output? (Base directory)
        //
        if (log.isDebugEnabled())
        {
            log.debug("Output directory base will be " + outputDirectory.getAbsolutePath());
        }
        tool.setOutputDirectory(outputDirectory.getAbsolutePath());

        // Tell ANTLR that we always want the output files to be produced in the output directory
        // using the same relative path as the input file was to the input directory.
        //
        tool.setForceRelativeOutput(true);

        // Where do we want ANTLR to look for .tokens and import grammars?
        //
        tool.setLibDirectory(libDirectory.getAbsolutePath());

        if (!sourceDirectory.exists()) {
            if (log.isInfoEnabled()) {
                log.info("No ANTLR grammars to compile in " + sourceDirectory.getAbsolutePath());
            }
            return;
        } else {
            if (log.isInfoEnabled()) {
                log.info("ANTLR: Processing source directory " + sourceDirectory.getAbsolutePath());
            }
        }

        // Set working directory for ANTLR to be the base source directory
        //
        tool.setInputDirectory(sourceDirectory.getAbsolutePath());

        try {

            // Now pick up all the files and process them with the Tool
            //
            processGrammarFiles(sourceDirectory, outputDirectory);

        } catch (InclusionScanException ie) {

            log.error(ie);
            throw new MojoExecutionException("Fatal error occured while evaluating the names of the grammar files to analyze");

        } catch (Exception e) {

            getLog().error(e);
            throw new MojoExecutionException(e.getMessage());
        }



        tool.process();

        // If any of the grammar files caused errors but did nto throw exceptions
        // then we should have accumulated errors in the counts
        //
        if (tool.getNumErrors() > 0) {
            throw new MojoExecutionException("ANTLR caught " + tool.getNumErrors() + " build errors.");
        }

        // All looks good, so we need to tel Maven about the sources that
        // we just created.
        //
        if (project != null) {
            // Tell Maven that there are some new source files underneath
            // the output directory.
            //
            addSourceRoot(this.getOutputDirectory());
        }

    }


    /**
     *
     * @param sourceDirectory
     * @param outputDirectory
     * @throws IOException
     * @throws InclusionScanException
     */
    private void processGrammarFiles(File sourceDirectory, File outputDirectory)
            throws IOException, InclusionScanException {
        // Which files under the source set should we be looking for as grammar files
        //
        SourceMapping mapping = new SuffixMapping("g", Collections.<String>emptySet());

        // What are the sets of includes (defaulted or otherwise).
        //
        Set<String> includes = getIncludesPatterns();

        // Now, to the excludes, we need to add the imports directory
        // as this is autoscanned for importd grammars and so is auto-excluded from the
        // set of gramamr fiels we shuold be analyzing.
        //
        excludes.add("imports/**");

        SourceInclusionScanner scan = new SimpleSourceInclusionScanner(includes, excludes);

        scan.addSourceMapping(mapping);
        Set<File> grammarFiles = scan.getIncludedSources(sourceDirectory, null);

        if (grammarFiles.isEmpty()) {
            if (getLog().isInfoEnabled()) {
                getLog().info("No grammars to process");
            }
        } else {

            // Tell the ANTLR tool that we want sorted build mode
            //
            tool.setMake(true);
            
            // Iterate each grammar file we were given and add it into the tool's list of
            // grammars to process.
            //
            for (File grammar : grammarFiles) {

                if (getLog().isDebugEnabled()) {
                    getLog().debug("Grammar file '" + grammar.getPath() + "' detected.");
                }


                String relPath = findSourceSubdir(sourceDirectory, grammar.getPath()) + grammar.getName();

                if (getLog().isDebugEnabled()) {
                    getLog().debug("  ... relative path is: " + relPath);
                }
                tool.addGrammarFile(relPath);

            }

        }


    }

    public Set<String> getIncludesPatterns() {
        if (includes == null || includes.isEmpty()) {
            return Collections.singleton("**/*.g");
        }
        return includes;
    }

    /**
     * Given the source directory File object and the full PATH to a
     * grammar, produce the path to the named grammar file in relative
     * terms to the {@code sourceDirectory}. This will then allow ANTLR to
     * produce output relative to the base of the output directory and
     * reflect the input organization of the grammar files.
     *
     * @param sourceDirectory The source directory {@link File} object
     * @param grammarFileName The full path to the input grammar file
     * @return The path to the grammar file relative to the source directory
     */
    private String findSourceSubdir(File sourceDirectory, String grammarFileName) {
        String srcPath = sourceDirectory.getPath() + File.separator;

        if (!grammarFileName.startsWith(srcPath)) {
            throw new IllegalArgumentException("expected " + grammarFileName + " to be prefixed with " + sourceDirectory);
        }

        File unprefixedGrammarFileName = new File(grammarFileName.substring(srcPath.length()));
	if ( unprefixedGrammarFileName.getParent()!=null ) {
	    return unprefixedGrammarFileName.getParent() + File.separator;
        }
	else {
            return "";
	}
    }
}
