// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2023 OKTET Labs Ltd. All rights reserved.
//
// Pipeline template for building test suite documentation.
//
// Required plugins:
//   - Pipeline: Stage Step
//   - Timestamper
//   - Copy Artifact
//
// Pipeline does:
//   1. Call preStartHook if needed (see "Available pipeline hooks").
//   2. Clone TE/TS/TS Conf/TS Rigs with revisions that you define.
//   3. Obtain DoxyRest if required.
//   4. Call buildDocHook().
//   5. Archive used revisions in an artifact.
//   6. Call archiveDocHook() to archive generated documentation.
//
// Default job parameters
// Note: tsconf_* parameters are present only if tsconf template parameter
// was set to true.
//
//   te_rev: TE revision, can be tag, branch or SHA-1
//   te_repo: TE repository (can also be set in TE_GIT_URL environment
//            variable in Jenkins; value of this parameter can
//            override the environment)
//   ts_rev: TS revision, can be tag, branch or SHA-1
//   ts_repo: test suite repository (can also be set in TS_GIT_URL
//            environment variable in Jenkins; value of this parameter
//            can override the environment)
//   tsconf_rev: TS Conf revision, can be tag, branch or SHA-1
//   tsconf_repo: ts-conf repository (can also be set in TSCONF_GIT_URL
//                environment variable in Jenkins; value of this parameter
//                can override the environment)
//   tsrigs_rev: TS rigs revision, can be tag, branch or SHA-1
//   tsrigs_repo: ts-rigs repository (can also be set in TSRIGS_GIT_URL
//                environment variable in Jenkins; value of this
//                parameter can override the environment)
//   get_revs_from: Jobs from which to get revisions of the last
//                  successful build (sticky default). It is assumed
//                  that revisions were saved in an artifact in `all.rev`
//                  file using teRevData API.
//                  This can be used to ensure that buildable revisions are
//                  used.
//
// Available parameters of pipeline template:
//   ts_name: Test suite name.
//   label: String. Execute the Pipeline on an agent available in the Jenkins
//          environment with the provided label. Required.
//   specificParameters: List of additional job parameters which can be
//                       used in test suite specific hooks. Optional.
//   triggersProvider: Closure that returns list of conditions when job starts
//                     automatically. Optional.
//   tsconf: if true, it is required to clone TS Conf repository (not all
//           test suites may need it).
//   doxyrest: if true, DoxyRest should be obtained from github.
//   send_on_status: list of build statuses when email should be sent
//                   at the end. Available statuses: OK, unsuccessful,
//                   fixed. Default list: ['fixed', 'unsuccessful'].
//
// Available pipeline hooks (see "Pipeline does" for understanding when hook
// is called):
//   preStartHook: Closure is called before TE/TS/TS Conf/TS Rigs checkout.
//                 Useful for overriding the te/ts/tsconf/tsrigs revisions or
//                 checkout of your tools. Optional parameter.
//   buildDocHook: Here documentation should be build. Required.
//   archiveDocHook: Here documentation should be archived in an artifact.
//                   Required.
//
// See "Pipeline templates" in jenkins/README.md for more information on
// how to use this template.

def call(Closure body) {
    // evaluate the body block, and collect configuration into the object
    def ctx = teCommon.create_pipeline_ctx(env, params)
    String doc_status = 'OK'
    Boolean build_status = true

    def get_revs_from = params.get_revs_from

    ctx.send_on_status = ['fixed', 'unsuccessful']

    // DELEGATE_FIRST means that the delegate is used firstly
    // to resolve properties. So that any property set in closure
    // body will be set in ctx.
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = ctx
    body()

    pipeline {

        agent { label ctx.label }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
            timestamps()
            copyArtifactPermission('*')
            disableConcurrentBuilds()
            checkoutToSubdirectory('ts-jenkins')
        }

        stages {
            stage('Job prepare') {
                steps {
                    script {
                        def triggersList = []
                        def paramsList

                        paramsList = teRun.get_repo_params(params,
                                                           ctx)

                        paramsList +=
                            string(name: 'get_revs_from',
                                   defaultValue: get_revs_from,
                                   description: 'Jobs providing revisions to use (sticky default; comma-separated list)')

                        if (ctx.containsKey('specificParameters')) {
                            paramsList += ctx['specificParameters']
                        }

                        if (ctx.containsKey('triggersProvider')) {
                            triggersList +=
                                ctx.triggersProvider(env, params)
                        }

                        properties([
                            parameters(paramsList),
                            pipelineTriggers(triggersList),
                        ])
                    }
                }
            }

            stage('Pre start') {
                steps {
                    script {
                        if (get_revs_from) {
                            ctx.revdata_try_load(get_revs_from)
                        }

                        if (ctx.containsKey('preStartHook')) {
                            ctx.preStartHook()
                        }
                    }
                }
            }

            stage('Clone ts-rigs sources') {
                when { expression { env.TSRIGS_GIT_URL } }
                steps {
                    script {
                        teRun.tsrigs_checkout(ctx)
                        teRun.tsrigs_load(ctx)
                    }
                }
            }

            stage('Clone TE and TS sources') {
                steps {
                    script {
                        teRun.te_checkout(ctx)
                        teRun.ts_checkout(ctx)
                    }
                }
            }

            stage('Clone ts-conf sources') {
                when { expression { ctx.tsconf } }
                steps {
                    script {
                        teRun.tsconf_checkout(ctx)
                    }
                }
            }

            stage("Get DoxyRest") {
                when { expression { ctx.doxyrest } }
                steps {
                    script {
                        sh """
                            if ! test -d doxyrest-2.1.2-linux-amd64 ; then
                                wget https://github.com/vovkos/doxyrest/releases/download/doxyrest-2.1.2/doxyrest-2.1.2-linux-amd64.tar.xz
                                tar -xf doxyrest-2.1.2-linux-amd64.tar.xz
                            fi
                        """
                        dir('doxyrest-2.1.2-linux-amd64') {
                            env.DOXYREST_PREFIX = pwd()
                        }
                    }
                }
            }

            stage("Build documentation") {
                steps {
                    script {
                        ctx.buildDocHook()
                    }
                }
            }

            stage("Archive") {
                steps {
                    script {
                        ctx.revdata_archive()

                        ctx.archiveDocHook()
                    }
                }
            }
        }

        post {
            unsuccessful {
                script {
                    doc_status = 'unsuccessful'
                    build_status = false
                }
            }
            fixed {
                script {
                    doc_status = 'fixed'
                    teEmail.email_set_trailer(ctx, 'fixed')
                }
            }
            cleanup {
                script {
                    if (doc_status in ctx.send_on_status) {
                        if (ctx.ts_name) {
                            teEmail.email_add_to_by_ids(ctx, ctx.ts_name)
                        }
                        teEmail.email_start(ctx)
                        teEmail.email_all_revs(ctx)
                        teEmail.email_post(ctx, build_status, [])
                    }
                }
            }
        }
    }
}
