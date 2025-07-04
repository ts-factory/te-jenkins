// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2023 OKTET Labs Ltd. All rights reserved.
//
// Helper for working with TE/TS/TS Conf/TS Rigs.

// Determine GIT URL for a given component (te/tsconf/tsrigs/ts),
// taking into account parameters and environment.
//
// Args:
//   ctx: pipeline context
//   component: component name
//
// Return:
//   URL
def get_url(ctx, component) {
    def url
    def var_name
    def source

    // For example, if component = 'te', we try the
    // following sources (stopping once we found
    // defined value):
    //
    // params.te_repo
    // TE_GIT_URL in context
    // TE_GIT_URL in environment

    var_name = "${component}_GIT_URL".toUpperCase()

    if ((url = params["${component}_repo"] ?: '')) {
        source = "pipeline parameter ${component}_repo"
    } else if ((url = ctx[var_name])) {
        source = "context variable ${var_name}"
    } else if ((url = env[var_name] ?: '')) {
        source = "environment variable ${var_name}"
    } else {
        return null
    }

    url = url.replaceAll(/__USER__/, "${env.USER}")
    println "Repository URL for '${component}' is obtained " +
            "from ${source}: ${url}"

    return url
}

// Determine revision for a given component (te/tsconf/tsrigs/ts),
// taking into account parameters and environment.
//
// Args:
//   ctx: pipeline context
//   component: component name
//
// Return:
//   Revision
def get_rev(ctx, component) {
    def rev
    def var_pref
    def source

    // For example, if component = 'te', we try the
    // following sources (stopping once we found
    // defined value):
    //
    // params.te_rev
    // TE_REV in context
    // TE_REV in environment
    // params.te_branch
    // TE_DEF_BRANCH in context

    var_pref = "${component}".toUpperCase()

    if ((rev = params["${component}_rev"] ?: '')) {
        source = "pipeline parameter ${component}_rev"
    } else if ((rev = ctx["${var_pref}_REV"])) {
        source = "context variable ${var_pref}_REV"
    } else if ((rev = env["${var_pref}_REV"] ?: '')) {
        source = "environment variable ${var_pref}_REV"
    } else if ((rev = params["${component}_branch"] ?: '')) {
        source = "pipeline parameter ${component}_branch"
    } else if ((rev = ctx["${var_pref}_DEF_BRANCH"])) {
        source = "context variable ${var_pref}_DEF_BRANCH"
    } else {
        // Jenkins assumes 'master' by default but 'main' is
        // encountered more often. Jenkins cannot detect
        // default branch.
        println "Choosing 'main' branch by default for ${component}"
        return 'main'
    }

    println "Revision for '${component}' is obtained " +
            "from ${source}: ${rev}"
    return rev
}

// Generic function for checking out a repository. It saves
// repository URL and revision in ctx.all_revs (so that it
// will be stored in artifacts), and also sets [component]_SRC
// variable in context (pointing to the directory with obtained
// sources).
//
// Args:
//   ctx: pipeline context
//   component: component name (used as a prefix (capitalized)
//              for variables and as a key in ctx.all_revs map)
//   url: source URL (if null, get_url() determines URL)
//   revision: branch, tag or SHA-1 (if null, get_rev()
//             determines revision)
//   target: target directory (if null, will be set to
//           the value of component argument)
//   do_poll: if true, this checkout should be taken into account by
//            pollSCM() trigger
//
// Return:
//   List of checkout details as per GitSCM module.
def generic_checkout(ctx, String component, String url = null,
                     String revision = null, String target = null,
                     Boolean do_poll = true) {

    def scm_vars
    String rev = revision ?: get_rev(ctx, component)
    String rev_got
    String repo = url ?: get_url(ctx, component)
    String var_prefix
    String prev_rev
    String branch

    if (!repo) {
        error "Repository URL is not defined for ${component}"
    }

    var_prefix = "${component}_".toUpperCase()

    dir (target ?: component) {
        scm_vars = git_checkout(repo, rev, do_poll)

        ctx["${var_prefix}SRC"] = pwd()

        // scm_vars.GIT_COMMIT can report wrong revision (of default
        // branch) when we check out specific revision and
        // move to "detached" state.
        rev_got = git_get_rev()
    }

    ctx.revdata_set(component, "${var_prefix}GIT_URL", repo)

    prev_rev = ctx.revdata_get(component, "${var_prefix}REV")
    ctx.revdata_set(component, "${var_prefix}REV", rev_got)

    if (prev_rev != rev_got) {
        // Clean branch if it was inherited from artifacts of upstream
        // job and revision was not taken from there.

        ctx.revdata_del(component, "${var_prefix}BRANCH" )
    }

    // 'detached' means that repository was checked out to a specific
    // changeset, not a branch. Hopefully nobody calls a real
    // branch 'detached'.
    if (scm_vars.GIT_BRANCH && scm_vars.GIT_BRANCH != 'detached') {
        ctx.revdata_set(component, "${var_prefix}BRANCH",
                        scm_vars.GIT_BRANCH - ~/^\/?origin\//)
    }

    if ((branch = ctx.revdata_get(component, "${var_prefix}BRANCH"))) {
        // Save branch name in environment - it can be used when generating
        // metadata in TE.
        //
        // Note: I add JENKINS_ prefix here because Jenkins environment
        // can already contain "${var_prefix}BRANCH" but in lower case
        // because there is a pipeline parameter with such name. And
        // Jenkins will process assignment in a case insensitive way,
        // i.e. if you have tsrigs_branch=X in environment and try
        // to do env["TSRISGS_BRANCH"]=Y, you will have
        // tsrigs_branch=Y in environment. This is not desirable
        // here.
        def env_name = "JENKINS_"

        if (component == "ts") {
            env_name += teCommon.str2id(ctx.ts_name) + "_"
        } else {
            env_name += var_prefix
        }
        env_name += "BRANCH"

        ctx.env[env_name] = branch
    }

    return scm_vars
}

// Version of generic_checkout() accepting map instead of list of
// arguments. It allows to call the function like
// generic_checkout(ctx: ctx, component: 'te')
// which may be more convenient and human readable.
def generic_checkout(Map args) {
    if (!args.containsKey('do_poll')) {
        args.do_poll = true
    }

    return generic_checkout(args.ctx, args.component, args.url,
                            args.revision, args.target,
                            args.do_poll)
}

// Checkout TE
//
// Args:
//   ctx: pipeline context
//   revision: branch, tag or SHA-1 (if null, get_rev() determines
//             revision)
//   target: target directory
//
// Return:
//   List of checkout details as per GitSCM module.
def te_checkout(ctx, String revision = null,
                String target = 'te') {
    def result

    result = generic_checkout(ctx, 'te', null, revision, target)

    // TE_BASE is required for TE build.
    env.TE_BASE = ctx.TE_SRC

    return result
}

// Checkout Test Suite.
//
// Args:
//   ctx: pipeline context
//   url: source URL (if null, get_url() determines URL)
//   revision: branch, tag or SHA-1 (if null, get_rev()
//             determines revision)
//
// Return:
//   List of checkout details as per GitSCM module.
def ts_checkout(ctx, String url = null,
                String revision = null) {
    return generic_checkout(ctx, 'ts', url, revision, ctx.ts_name)
}

// Checkout TS Conf.
//
// Args:
//   ctx: pipeline context
//   revision: branch, tag or SHA-1 (if null, get_rev()
//             determines revision)
//   target: target directory
def tsconf_checkout(ctx, String revision = null,
                    String target = 'ts-conf') {

    return generic_checkout(ctx, 'tsconf', null, revision, target)
}

// Checkout TS rigs.
//
// Args:
//   ctx: pipeline context
//   revision: branch, tag or SHA-1 (if null, get_rev()
//             determines revision)
//   target: target directory
def tsrigs_checkout(ctx, String revision = null,
                    String target = 'ts-rigs') {
    return generic_checkout(ctx, 'tsrigs', null, revision, target)
}

// Load site-specific Jenkins configuration from TS Rigs.
//
// Args:
//   ctx: pipeline context
def tsrigs_load(ctx) {
    def tsdefs_path
    def common_env_path
    def common_env

    // Export common environment variables
    common_env_path = "${ctx.TSRIGS_SRC}/jenkins/"
    common_env_path += "common.groovy"

    if (fileExists(common_env_path)) {
        common_env = load "${common_env_path}"
        common_env.set_defs(ctx)
    }

    // Getting TS-specific variables
    tsdefs_path = "${ctx.TSRIGS_SRC}/jenkins/defs/"
    tsdefs_path += "${ctx.ts_name}/defs.groovy"

    if (fileExists(tsdefs_path)) {
        def tsdefs = load tsdefs_path

        tsdefs.set_defs(ctx)

        if (ctx.TS_MAIL_FROM) {
            teEmail.email_set_from(ctx, ctx.TS_MAIL_FROM)
        }

        if (ctx.TS_MAIL_TO) {
            teEmail.email_add_to(ctx, ctx.TS_MAIL_TO)
        }
    }

    /*
     * Make sure tsrigs does not overwrite update job
     * artifact.
     */
    ctx.revdata_export()
}

// Run Test suite with specific options.
//
// Args:
//   ctx: pipeline context
//   cfg: Optional parameter to set --cfg
//   options: A groovy list with options for run.sh
def run(ctx, String cfg, options) {
    dir('run') {

        def start_options = []
        Boolean rm_te_logs = false
        String te_logs
        String cmd
        String ts_run_pid

        env.TE_BUILD = "${env.WORKSPACE}/build"

        if (cfg != "") {
            start_options.add("--cfg=${cfg}")
        }

        if (fileExists(env.TE_WORKSPACE_DIR)) {
            rm_te_logs = true
            env.TE_TMP = sh(
              script: "mktemp -d ${env.TE_WORKSPACE_DIR}/te_tmp.${cfg}.XXX",
              returnStdout: true).trim()
            te_logs = sh(
              script: "mktemp -d ${env.TE_WORKSPACE_DIR}/te_log.${cfg}.XXX",
              returnStdout: true).trim()
            start_options.add("--log-dir=${te_logs}")
        } else {
            te_logs = pwd()
        }

        env.TE_LOG_RAW="${te_logs}/log.raw"
        env.TE_LOG_BUNDLE="${te_logs}/raw_log_bundle.tpxz"

        def opts = start_options.join(' ') + ' ' + options.join(' ')
        def run_sh_err = null

        // JUnit log can be used to obtain testing statistics after
        // tests are run (see statistics() here).
        opts += " --log-junit=log.junit"
        // TRC statistics in text form is used by Bublik.
        opts += " --trc-txt=trc-stats.txt"

        cmd = "${ctx.TE_SRC}/jenkins/scripts/te_run_wrap "
        cmd += "${ctx.TS_SRC}/scripts/run.sh ${opts}"
        try {
            env.TE_RUN_WRAP_PREFIX = pwd() + "/te_run"
            ts_run_pid = sh(script: "${cmd}", returnStdout: true).trim()

            sh("${ctx.TE_SRC}/jenkins/scripts/te_run_wait " +
               "${ts_run_pid}")
        } catch (err) {
            run_sh_err = err
        } finally {
            // If testing terminated already, this should be harmless.
            // Otherwise this will ensure that it terminates in a normal
            // way in case of pipeline abort.
            sh("${ctx.TE_SRC}/jenkins/scripts/te_run_wait " +
               "${ts_run_pid} stop")

            if (rm_te_logs) {
                if (fileExists(env.TE_LOG_BUNDLE)) {
                    sh "mv ${env.TE_LOG_BUNDLE} ."
                }
                sh "rm -rf '${te_logs}'"
            }

            if (run_sh_err != null)
                error('ERROR: run.sh failed with: ' + run_sh_err.toString())
        }
    }
}

// Check whether working directory is empty
def is_wdir_empty() {
    def content = sh(script: "ls", returnStdout: true).trim()

    return !content
}

// Save common artifacts for test suite
def archive() {
    dir('run') {
        if (!is_wdir_empty())
            archiveArtifacts artifacts: '**', excludes: 'log.txt'
    }
}

// Cleanup previous run files
def cleanup() {
    sh "rm -rf run"
}

// Strip run build to archive
def strip() {
    dir('run') {
        // Remove secondary log files (which may be generated from
        // raw log bundle).
        sh """
           rm -f log.raw
           rm -f log_plain.xml
           rm -f log_struct.xml
           rm -fr html
           rm -fr caps
           """
    }
}

// Get JUnit statistics about tests.
// This required JUnit Jenkins plugin.
//
// Return:
//    Test statistics object
def statistics() {
    def ts_stats = null

    dir('run') {
        if (fileExists('log.junit')) {
            ts_stats = junit testResults: 'log.junit', allowEmptyResults: true
        }
    }

    return ts_stats
}

// Just a wrapper for checkout from git.
// This requires "Pipeline: SCM step" plugin.
//
// Args:
//   url: URL for checkout
//   revision: branch, tag or SHA-1
//   do_poll: if true, this checkout should be taken into account by
//            pollSCM() trigger
//
// Return:
//   List of checkout details as per GitSCM module.
def git_checkout(String url, String revision, Boolean do_poll = true) {
    def result = null
    def scm_params = [
        $class: 'GitSCM',
        userRemoteConfigs: [[url: url]],
        // Previously CloneOption was used here to configure
        // shallow copy with depth = 100. Unfortunately it
        // caused problems with some repositories (DPDK, OVS) where
        // git hanged for a long time.
    ]

    if (revision) {
        scm_params.branches = [[name: revision]]
    }

    // changelog should not influence polling, but in fact
    // it does; it appears poll = false alone does not disable
    // polling if changelog is still enabled.
    result = checkout(scm: scm_params, poll: do_poll, changelog: do_poll)

    // This cleans .git directory. It can grow very large after
    // repeated checkouts in the same directory when shallow clone
    // is configured as it is done above with CloneOption (that was
    // actually observed for unbound tool).
    //
    // It seems there is no way to do this via checkout step:
    // https://issues.jenkins.io/browse/JENKINS-13493
    sh('git gc')

    return result
}

// Get git revision in the current directory.
def git_get_rev() {
    sh(script: "git show --format=%H --quiet", returnStdout: true).trim()
}

// Helper function to get nodes with a given label.
//
// Args:
//    label: label to search
def get_nodes(String label) {
    def nodes = jenkins.model.Jenkins.instance.nodes

    def filteredNodes = nodes.findAll { node ->
        node.labelString.contains("${label}")
    }
    return filteredNodes.collect { node ->
        node.name
    }
}

// Get specifications of repository-related parameters for
// some entity (for example, TE).
//
// Args:
//   params: pipeline parameters
//   entity: entity name
//   prefix: prefix for the related parameters
//   sticky_repo_params: if true, [prefix]_repo becomes sticky and
//                       [prefix]_branch parameter with sticky
//                       default is added.
//
// Return:
//   List of parameters specifications
def entity_params(params, entity, prefix, sticky_repo_params) {
    def specList = [
        string(name: "${prefix}_rev", defaultValue: '',
               description: "${entity} revision (overwrites branch)")
    ]

    if (sticky_repo_params) {
        specList.addAll([
            string(name: "${prefix}_branch",
                   defaultValue: params["${prefix}_branch"],
                   description: "${entity} branch (sticky default)"),
            string(name: "${prefix}_repo",
                   defaultValue: params["${prefix}_repo"],
                   description: "${entity} repository (sticky default)")
                   ])
    } else {
        specList.addAll([
            string(name: "${prefix}_repo",
                   description: "${entity} repository")
                   ])
    }

    return specList
}

// Get specifications of pipeline parameters for setting revisions,
// branches and repositories.
//
//  Args:
//    params: current pipeline parameters
//    tmplParams: pipeline template parameters
//
//  Return:
//    list of parameters specifications
def get_repo_params(params, tmplParams) {

    def paramsList = []

    paramsList.addAll(entity_params(params, "Test Environment", "te",
                                    tmplParams.sticky_repo_params))
    paramsList.addAll(entity_params(params, "Test Suite", "ts",
                                    tmplParams.sticky_repo_params))

    if (tmplParams.tsconf) {
        paramsList.addAll(entity_params(
                              params,
                              "Test suites shared configuration", "tsconf",
                              tmplParams.sticky_repo_params))
    }

    paramsList.addAll(entity_params(params, "Site-specific configuration",
                                    "tsrigs", tmplParams.sticky_repo_params))

    return paramsList
}

// Set LOGS_PATH (path to logs in logs storage) and
// HTML_LOGS (URL to logs in web interface) variables
// in pipeline context.
//
// Args:
//   ctx: pipeline context
def define_logs_paths(ctx) {
    if (!ctx.LOGS_PATH && !ctx.TS_LOGS_SUBPATH) {
        return
    }

    ctx.LOGS_PATH = ctx.LOGS_PATH ?:
                    ctx.TS_LOGS_SUBPATH +
                    ctx.env.TE_META_CAMPAIGN_DATE.replaceAll(/-/, '/') +
                    '/' + params.ts_cfg + '-' + env.BUILD_NUMBER

    if (ctx.TS_LOGS_URL_PREFIX) {
        ctx.HTML_LOGS = ctx.TS_LOGS_URL_PREFIX +
                        ctx.LOGS_PATH + '/'
    } else {
        ctx.HTML_LOGS = ""
    }
}

// Check whether node with a given label is available.
// This helps to avoid indefinite hanging of Jenkins job.
// If label is not found, this function aborts the current
// pipeline with error message.
//
// Args:
//   label: node label to check
def check_label(label) {
    Boolean label_exists = false

    jenkins.model.Jenkins.instance.nodes.find {
        node ->

        if (node.labelString.contains(label)) {
            label_exists = true
            return true
        }

        return false
    }

    if (!label_exists) {
        error("Cannot find node with label ${label}")
    }
}

// Publish testing logs (copy them to specified location and
// export them to Bublik web application).
//
// LOGS_PATH variable should be set to the location
// where copy of the logs should be saved.
// HTML_LOGS variable should be set to URL of the logs
// to make export to Bublik possible.
// define_logs_paths() can be used to set these variables.
//
// Export to Bublik happens only when TS_BUBLIK_URL is set in
// test suite specific defines.
//
// Args:
//   ctx: pipeline context
def publish_logs(ctx) {
    if (ctx.PUBLISH_LOGS_NODE) {
        check_label(ctx.PUBLISH_LOGS_NODE)

        build(job: 'publish-logs', wait: true,
            parameters: [
                [ $class: 'StringParameterValue',
                  name: 'job_to_copy_from',
                  value: env.JOB_NAME ],
                [ $class: 'StringParameterValue',
                  name: 'publish_to',
                  value: ctx.LOGS_PATH ],
                [ $class: 'StringParameterValue',
                  name: 'logs_node',
                  value: ctx.PUBLISH_LOGS_NODE ],
                [ $class: 'StringParameterValue',
                  name: 'art_srv_id',
                  value: ctx.TS_ARTIFACTORY ],
                [ $class: 'StringParameterValue',
                  name: 'art_repo_name',
                  value: ctx.TS_ARTIFACTORY_REPO ]
            ])

        if (ctx.TS_BUBLIK_URL && ctx.HTML_LOGS) {
            check_label('bublik-import')
            build(job: 'bublik-import', wait: true,
                parameters: [
                    [ $class: 'StringParameterValue',
                      name: 'bublik_url',
                      value: ctx.TS_BUBLIK_URL ],
                    [ $class: 'StringParameterValue',
                      name: 'logs_url', value: ctx.HTML_LOGS ]
                ])
        }
    }
}

// Execute closure under a lock.
//
// Args:
//   ctx: pipeline context
//   body: closure to execute
def cfg_lock(ctx, Closure body) {
    if (ctx.params.lock) {
        lock(ctx.params.lock, body)
    } else if (ctx.cfgLockHook) {
        ctx.cfgLockHook(ctx, body)
    } else {
        lock(ctx.params.ts_cfg, body)
    }
}
