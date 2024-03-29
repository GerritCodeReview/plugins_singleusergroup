// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.singleusergroup;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.GroupDescription;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.server.IdentifiedUser;
import com.google.inject.Inject;
import java.util.Map;
import org.junit.Test;

@NoHttpd
@TestPlugin(
    name = "singleusergroup",
    sysModule = "com.googlesource.gerrit.plugins.singleusergroup.SingleUserGroup$PluginModule")
public class SingleUserGroupTest extends LightweightPluginDaemonTest {
  @Inject private RequestScopeOperations requestScopeOperations;

  @Test
  public void testSuggestion() throws Exception {
    // No ability to modify account and therefore no ACL to see secondary email
    requestScopeOperations.setApiUser(user.id());
    Map<String, GroupInfo> groups = gApi.groups().list().withSuggest("adm").getAsMap();
    assertThat(groups).containsKey("user/Administrator (admin)");
  }

  @Test
  public void testAllNumericUserGroup() throws Exception {
    String numericUsername = "123456";
    TestAccount numericAccount = accountCreator.create(numericUsername);
    IdentifiedUser numericUser = identifiedUserFactory.create(numericAccount.id());

    GroupDescription.Basic numericUserGroup =
        groupBackend.get(AccountGroup.UUID.parse("user:" + numericUsername));
    assertThat(numericUserGroup).isNotNull();

    assertThat(groupBackend.membershipsOf(numericUser).contains(numericUserGroup.getGroupUUID()))
        .isTrue();
  }
}
