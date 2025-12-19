load("@com_googlesource_gerrit_bazlets//:gerrit_plugin.bzl", "gerrit_plugin", "gerrit_plugin_tests")

gerrit_plugin(
    name = "singleusergroup",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: singleusergroup",
        "Gerrit-Module: com.googlesource.gerrit.plugins.singleusergroup.SingleUserGroup$PluginModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
)

gerrit_plugin_tests(
    name = "singleusergroup_tests",
    srcs = glob(["src/test/java/**/*Test.java"]),
    tags = ["singleusergroup"],
    visibility = ["//visibility:public"],
    runtime_deps = [":singleusergroup__plugin"],
)
