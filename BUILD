load("//tools/bzl:plugin.bzl", "gerrit_plugin")

gerrit_plugin(
    name = "singleusergroup",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: singleusergroup",
        "Gerrit-Module: com.googlesource.gerrit.plugins.singleusergroup.SingleUserGroup$Module",
    ],
    resources = glob(["src/main/resources/**/*"]),
)
