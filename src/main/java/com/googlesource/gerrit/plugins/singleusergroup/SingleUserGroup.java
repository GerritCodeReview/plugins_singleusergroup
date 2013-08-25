// Copyright (C) 2012 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gerrit.common.data.GroupDescription;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountExternalId;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountControl;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Makes a group out of each user.
 * <p>
 * UUIDs for the groups are derived from the unique username attached to the
 * account. A user can only be used as a group if it has a username.
 */
@Singleton
public class SingleUserGroup implements GroupBackend {
  private static final Logger log =
      LoggerFactory.getLogger(SingleUserGroup.class);

  private static final String UUID_PREFIX = "user:";
  private static final String NAME_PREFIX = "user/";
  private static final String ACCOUNT_PREFIX = "userid/";
  private static final String ACCOUNT_ID_PATTERN = "[1-9][0-9]*";
  private static final int MAX = 10;

  public static class Module extends AbstractModule {
    @Override
    protected void configure() {
      DynamicSet.bind(binder(), GroupBackend.class).to(SingleUserGroup.class);
    }
  }

  private final SchemaFactory<ReviewDb> schemaFactory;
  private final AccountCache accountCache;
  private final AccountControl.Factory accountControlFactory;

  @Inject
  SingleUserGroup(SchemaFactory<ReviewDb> schemaFactory,
      AccountCache accountCache,
      AccountControl.Factory accountControlFactory) {
    this.schemaFactory = schemaFactory;
    this.accountCache = accountCache;
    this.accountControlFactory = accountControlFactory;
  }

  @Override
  public boolean handles(AccountGroup.UUID uuid) {
    return uuid.get().startsWith(UUID_PREFIX);
  }

  @Override
  public GroupMembership membershipsOf(final IdentifiedUser user) {
    ImmutableList.Builder<AccountGroup.UUID> groups = ImmutableList.builder();
    groups.add(uuid(user.getAccountId()));
    if (user.getUserName() != null) {
      groups.add(uuid(user.getUserName()));
    }
    return new ListGroupMembership(groups.build());
  }

  @Override
  public GroupDescription.Basic get(final AccountGroup.UUID uuid) {
    String ident = username(uuid);
    final AccountState state;
    if (ident.matches(ACCOUNT_ID_PATTERN)) {
      state = accountCache.get(new Account.Id(Integer.parseInt(ident)));
    } else if (ident.matches(Account.USER_NAME_PATTERN)) {
      state = accountCache.getByUsername(ident);
    } else {
      return null;
    }
    if (state != null) {
      final String name = nameOf(uuid, state);
      return new GroupDescription.Basic() {
        @Override
        public AccountGroup.UUID getGroupUUID() {
          return uuid;
        }

        @Override
        public String getName() {
          return name;
        }

        @Override
        @Nullable
        public String getEmailAddress() {
          return state.getAccount().getPreferredEmail();
        }

        @Override
        @Nullable
        public String getUrl() {
          return null;
        }
      };
    }
    return null;
  }

  @Override
  public Collection<GroupReference> suggest(String name,
      @Nullable ProjectControl project) {
    if (name.startsWith(NAME_PREFIX)) {
      name = name.substring(NAME_PREFIX.length());
    } else if (name.startsWith(ACCOUNT_PREFIX)) {
      name = name.substring(ACCOUNT_PREFIX.length());
    }
    if (name.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      AccountControl ctl = accountControlFactory.get();
      Set<Account.Id> ids = Sets.newHashSet();
      List<GroupReference> matches = Lists.newArrayListWithCapacity(MAX);
      String a = name;
      String b = end(a);
      ReviewDb db = schemaFactory.open();
      try {
        if (name.matches(ACCOUNT_ID_PATTERN)) {
          Account.Id id = new Account.Id(Integer.parseInt(name));
          if (db.accounts().get(id) != null) {
            add(matches, ids, ctl, id);
            return matches;
          }
        }

        if (name.matches(Account.USER_NAME_PATTERN)) {
          for (AccountExternalId e : db.accountExternalIds().suggestByKey(
              new AccountExternalId.Key(AccountExternalId.SCHEME_USERNAME + a),
              new AccountExternalId.Key(AccountExternalId.SCHEME_USERNAME + b),
              MAX)) {
            if (!e.getSchemeRest().startsWith(a)) {
              break;
            }
            add(matches, ids, ctl, e.getAccountId());
          }
        }

        for (Account p : db.accounts().suggestByFullName(a, b, MAX)) {
          if (!p.getFullName().startsWith(a)) {
            break;
          }
          add(matches, ids, ctl, p.getId());
        }

        for (Account p : db.accounts().suggestByPreferredEmail(a, b, MAX)) {
          if (!p.getPreferredEmail().startsWith(a)) {
            break;
          }
          add(matches, ids, ctl, p.getId());
        }

        for (AccountExternalId e : db.accountExternalIds()
            .suggestByEmailAddress(a, b, MAX)) {
          if (!e.getEmailAddress().startsWith(a)) {
            break;
          }
          add(matches, ids, ctl, e.getAccountId());
        }

        return matches;
      } finally {
        db.close();
      }
    } catch (OrmException err) {
      log.warn("Cannot suggest users", err);
      return Collections.emptyList();
    }
  }

  private static String end(String a) {
    char next = (char) (a.charAt(a.length() - 1) + 1);
    return a.substring(0, a.length() - 1) + next;
  }

  private void add(List<GroupReference> matches, Set<Account.Id> ids,
      AccountControl ctl, Account.Id id) {
    if (!ids.add(id) || !ctl.canSee(id)) {
      return;
    }

    AccountState state = accountCache.get(id);
    if (state == null) {
      return;
    }

    AccountGroup.UUID uuid;
    if (state.getUserName() != null) {
      uuid = uuid(state.getUserName());
    } else {
      uuid = uuid(id);
    }
    matches.add(new GroupReference(uuid, nameOf(uuid, state)));
  }

  private static String username(AccountGroup.UUID uuid) {
    checkUUID(uuid);
    return uuid.get().substring(UUID_PREFIX.length());
  }

  private static AccountGroup.UUID uuid(Account.Id ident) {
    return uuid(Integer.toString(ident.get()));
  }

  private static AccountGroup.UUID uuid(String username) {
    return new AccountGroup.UUID(UUID_PREFIX + username);
  }

  private static void checkUUID(AccountGroup.UUID uuid) {
    checkArgument(
      uuid.get().startsWith(UUID_PREFIX),
      "SingleUserGroup does not handle %s", uuid.get());
  }

  private static String nameOf(AccountGroup.UUID uuid, AccountState account) {
    StringBuilder buf = new StringBuilder();
    if (account.getAccount().getFullName() != null) {
      buf.append(account.getAccount().getFullName());
    }
    if (account.getUserName() != null) {
      if (buf.length() > 0) {
        buf.append(" (").append(account.getUserName()).append(")");
      } else {
        buf.append(account.getUserName());
      }
    } else if (buf.length() > 0) {
      buf.append(" (").append(account.getAccount().getId().get()).append(")");
    } else {
      buf.append(account.getAccount().getId().get());
    }

    String ident = username(uuid);
    if (ident.matches(ACCOUNT_ID_PATTERN)) {
      buf.insert(0, ACCOUNT_PREFIX);
    } else {
      buf.insert(0, NAME_PREFIX);
    }
    return buf.toString();
  }
}
