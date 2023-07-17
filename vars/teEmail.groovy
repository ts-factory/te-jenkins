// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2023 OKTET Labs Ltd. All rights reserved.
//
// Helper for email generation.

// Set email sender.
//
// Args:
//   ctx: pipeline context
//   address: email address
def email_set_from(ctx, String address) {
    ctx.EMAIL_FROM = address
}

// Add email recipient.
//
// Args:
//   ctx: pipeline context
//   address: email address
def email_add_to(ctx, String address) {
    if (ctx.EMAIL_TO == null) {
        ctx.EMAIL_TO = ""
    }
    ctx.EMAIL_TO += "${address};"
}

// Set prefix in email subject.
// By default it is "[CI <jobname>]".
//
// Args:
//   ctx: pipeline context
//   prefix: prefix string
def email_set_prefix(ctx, String prefix) {
    ctx.EMAIL_PREFIX = prefix
}

// Set trailer in email subject.
//
// Args:
//   ctx: pipeline context
//   trailer: trailer string
def email_set_trailer(ctx, String trailer) {
    ctx.EMAIL_TRAILER = trailer
}

// Get path to a file where email message text is constructed.
//
// Return:
//   Email file path relative to WORKSPACE
def email_file_get() {
    return "${env.WORKSPACE}/email.txt"
}

// Reset email message: make it clean and print basic job info
def email_start() {
    def email_file = email_file_get()
    sh "echo > ${email_file}"

    email_variable_message('WORKSPACE', env.WORKSPACE)
    email_variable_message('JOB_NAME', env.JOB_NAME)
    email_variable_message('NODE_NAME', env.NODE_NAME)
    email_newline()
    email_message("Build URL: ${env.BUILD_URL}")
    email_message("Timestamp: ${env.BUILD_TIMESTAMP}")
    email_newline()
}

// Add message to email
//
// Args:
//   message: message string
def email_message(String message) {
    def email_file = email_file_get()
    sh "echo '${message}' >> ${email_file}"
}

// Just wrapper for adding empty line
def email_newline() {
    email_message("")
}

// Add variable to email
//
// Args:
//   name: name of variable
//   value: value of variable
def email_variable_message(String name, String value) {
    email_message("${name}=${value}")
}

// Add all revisions to email text. Revision are taken
// from the map constructed with help of teRevData.
//
// Args:
//   revs: map with revisions
def email_all_revs(Map revs) {
    def revs_only = []

    teRevData.iterate_values(revs, {
        component, variable, value ->
        if (variable ==~ /.*_REV$/) {
            revs_only.add("${variable}=${value}")
        }
    })

    email_newline()
    email_message("Revisions:")
    revs_only.sort().each {
        value ->
        email_message(value)
    }
}

// Add stage to email
//
// Args:
//   stage: stage name
def email_stage(String stage) {
    email_message("STAGE: ${stage}")
}

// Post email by status
//
// Args:
//   ctx: pipeline context
//   status: status of build
//   providers: recipient providers, i.e. requestor() or culprits() etc...
def email_post(ctx, status, providers) {
    def email_file = email_file_get()
    def prefix = ctx.EMAIL_PREFIX ?: "[CI ${env.JOB_NAME}]"
    def subject

    email_newline()
    if (status) {
        email_message("OK")
    } else {
        email_message("FAIL")
    }
    email_newline()

    subject = "${prefix} job ${currentBuild.displayName}: "
    subject += "${currentBuild.currentResult}"

    if (ctx.EMAIL_TRAILER) {
        subject += " ${ctx.EMAIL_TRAILER}"
    }

    emailext (
        subject: "${subject}",
        to: ctx.EMAIL_TO,
        from: ctx.EMAIL_FROM,
        recipientProviders: providers,
        attachLog: true,
        attachmentsPattern: '**/trc-brief.html',
        body: '${FILE, path="' + email_file + '"}'
    )
}
