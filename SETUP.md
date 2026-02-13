[SPDX-License-Identifier: Apache-2.0]::
[Copyright (C) 2025 OKTET Ltd.]::

# Setting up Jenkins testing for your test suite

This document describes the exact steps necessary to achieve a working
Jenkins testing setup.

## Required Jenkins plugins

- Pipeline: Stage Step
- Pipeline: SCM step
- Timestamper
- Email Extension
- Copy Artifact
- JUnit Plugin
- Lockable Resources
- Parameterized Scheduler
- Git
- Pipeline: Groovy
- Pipeline: Declarative
- Pipeline: Build Step
- Pipeline Utility Steps

## Set up git credentials

Go to "Manage Jenkins > Credentials > (global) > Add credentials"
or use this URL: /manage/credentials/store/system/domain/_/newCredentials

```
Kind: Username with password
Score: Global
Username: Your github username
Password: Your github token
```

## Set up the global shared library

Go to "Manage Jenkins > System > Global Trusted Pipeline Libraries"
or use this URL: /manage/configure#global-trusted-pipeline-libraries

Add new library:
```
Name: teLib
Default version: main (or other branch name)
Retrieval method: Modern SCM (set the git repo URL and credentials)
Library path: leave empty
```

## Create utility jobs

Create two root jobs: publish-logs and bublik-import.

For both of them, do the following:

1. Go to the job page
2. Click "Configure"
3. Go to the "Pipelines" section
4. Set "Definition" to "Pipeline script from SCM"
5. Set "SCM" to "Git"
6. Enter your ts-jenkins repository URL
7. Select your git credentials
8. Set the branch specifier to your main ts-jenkins branch name
9. Set "Script path" to "pipelines/publish-logs" or
   "pipelines/bublik-import"
10. Click "Save"

## Prepare Jenkins scripts

### ts-rigs

Go to your ts-rigs repository and create the following file:
jenkins/defs/YOUR\_TS\_NAME/defs.groovy

```groovy
def set_defs(ctx) {
    ctx.TE_GIT_URL = <FIXME>
    ctx.TE_DEF_BRANCH = <FIXME>

    ctx.TS_GIT_URL = <FIXME>
    ctx.TS_DEF_BRANCH = <FIXME>

    ctx.TSCONF_GIT_URL = <FIXME>
    ctx.TSCONF_DEF_BRANCH = <FIXME>

    ctx.TSRIGS_GIT_URL = <FIXME>
    ctx.TSRIGS_DEF_BRANCH = <FIXME>

    // Variables required for publish-logs
    ctx.PUBLISH_LOGS_NODE = 'ts-logs'
    ctx.TS_LOGS_SUBPATH = 'your test suite name with a trailing slash'

    // Variables required for bublik-import
    ctx.TS_BUBLIK_URL = 'URL to your bublik instance'
    // Assuming you want to import logs from TE log server that comes
    // with bublik
    ctx.TS_LOGS_URL_PREFIX = ctx.TS_BUBLIK_URL + 'logs/'
}

return this
```

Fill in the blanks and adjust the contents as needed. Add
`GIT_URL/DEF_BRANCH` lines for other components required to build your
test suite.

If you would like to have a schedule for your test runs, create another
script right next to defs.groovy and call it "schedule" or "nightly".

```groovy
@Library('teLib') _

teScheduledRunPipeline {
    label = 'main'

    schedule = '''
00 18 * * * % ts_cfg=configname;job=run;get_revs_from=update;
'''

    specificParameters = [
        string(name: 'ts_cfg', defaultValue: '',
               description: 'Tested configuration'),
        string(name: 'ts_opts', defaultValue: '',
               description: 'Additional options for run.sh'),
        string(name: 'get_revs_from', defaultValue: '',
               description: 'From which jobs to get revisions to build'),
        booleanParam(name: 'with_tce', defaultValue: false,
                     description: 'Add --tce option for run.sh'),
        <FIXME>
    ]
}
```

Create a Jenkins job and point it at this script. This job will execute
the job specified in its `job` parameter and pass it the rest of its
parameters.

Add more parameters to the `specificParameters` array based on the
parameters of your `run` job (see next section).

### Your test suite

In your test suite repository, create a directory for Jenkins scripts.
There, create the following files:

update
```groovy
@Library('teLib') _

teTsPipeline {
    label = <FIXME>
    ts_name = <FIXME>

    tsconf = true
    sticky_repo_params = true
    update_job = true

    optionsProvider = {
        return [
            '-q',
            '--build-only',
        ]
    }

    triggersProvider = {
        return [
            pollSCM('H * * * *'),
        ]
    }
}
```

run
```groovy
@Library('teLib') _

teTsPipeline {
    label = <FIXME>
    ts_name = <FIXME>
    tsconf = true
    publish_logs = true
    concurrent_builds = true

    specificParameters = [
        stringParam(name: <FIXME>,
                    defaultValue: <FIXME>,
                    description: <FIXME>),
        booleanParam(name: need_option,
                     defaultValue: false,
                     description: 'additional run.sh option required'),
    ]

    optionsProvider = {
        def opts = [
            '-q',
            '--steal-cfg',
            '--release-cfg',
            '--tester-req=!BROKEN'
        ]

        // Add more options based on job params
        if (params.need_option) {
            opts.add('--some-option')
        }

        return opts
    }

    preRunHook = {
        // If you need a clean build
        print('Remove artifacts from previous build')
        sh "rm -fr build"

        // Do this for each of your additional components
        teRun.generic_checkout(teCtx, 'insert component name')
    }
}
```

Fill in the blanks and adjust the contents as necessary. Add more
actions if your test suite needs that. Create Jenkins jobs and point
them at these scripts.
