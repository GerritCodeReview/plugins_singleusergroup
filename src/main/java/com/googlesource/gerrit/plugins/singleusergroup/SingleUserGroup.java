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
  private static final int MAX = 10;

  public static class Module extends AbstractModule {
    @Override
    protected void configure() {
      bind(SingleUserGroup.class);
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
    return new GroupMembership() {
      @Override
      public boolean contains(AccountGroup.UUID uuid) {
        return username(uuid).equals(user.getUserName());
      }

      @Override
      public boolean containsAnyOf(Iterable<AccountGroup.UUID> groups) {
        for (AccountGroup.UUID uuid : groups) {
          if (contains(uuid)) {
            return true;
          }
        }
        return false;
      }

      @Override
      public Set<AccountGroup.UUID> getKnownGroups() {
        return Collections.emptySet();
      }
    };
  }

  @Override
  public GroupDescription.Basic get(final AccountGroup.UUID uuid) {
    AccountState state = accountCache.getByUsername(username(uuid));
    if (state != null) {
      final String name = nameOf(state);
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
        public boolean isVisibleToAll() {
          return false;
        }
      };
    }
    return null;
  }

  @Override
  public Collection<GroupReference> suggest(String name) {
    if (name.startsWith(NAME_PREFIX)) {
      name = name.substring(NAME_PREFIX.length());
    }
    if (name.length() < 3) {
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
        if (name.matches("[1-9][0-9]*")) {
          Account.Id id = new Account.Id(Integer.parseInt(name));
          if (db.accounts().get(id) != null && ctl.canSee(id)) {
            add(matches, id);
            if (!matches.isEmpty()) {
              return matches;
            }
          }
        }

        if (name.matches(Account.USER_NAME_PATTERN)) {
          for (AccountExternalId e : db.accountExternalIds().suggestByKey(
              new AccountExternalId.Key(AccountExternalId.SCHEME_USERNAME + a),
              new AccountExternalId.Key(AccountExternalId.SCHEME_USERNAME + b),
              MAX)) {
            if (!e.getSchemeRest().startsWith(a)) {
              break;
            } else if (ids.add(e.getAccountId())
                && ctl.canSee(e.getAccountId())) {
              add(matches, e.getAccountId());
            }
            if (matches.size() == MAX) {
              return matches;
            }
          }
        }

        for (Account p : db.accounts().suggestByFullName(a, b, MAX)) {
          if (!p.getFullName().startsWith(a)) {
            break;
          } else if (ids.add(p.getId()) && ctl.canSee(p.getId())) {
            add(matches, p.getId());
          }
          if (matches.size() == MAX) {
            return matches;
          }
        }

        for (Account p : db.accounts().suggestByPreferredEmail(a, b, MAX)) {
          if (!p.getPreferredEmail().startsWith(a)) {
            break;
          } else if (ids.add(p.getId()) && ctl.canSee(p.getId())) {
            add(matches, p.getId());
          }
          if (matches.size() == MAX) {
            return matches;
          }
        }

        for (AccountExternalId e : db.accountExternalIds()
            .suggestByEmailAddress(a, b, MAX)) {
          if (!e.getEmailAddress().startsWith(a)) {
            break;
          } else if (ids.add(e.getAccountId()) && ctl.canSee(e.getAccountId())) {
            add(matches, e.getAccountId());
          }
          if (matches.size() == MAX) {
            return matches;
          }
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

  private void add(List<GroupReference> matches, Account.Id id) {
    AccountState state = accountCache.get(id);
    if (state != null && state.getUserName() != null) {
      matches.add(new GroupReference(uuid(state.getUserName()), nameOf(state)));
    }
  }

  private static String username(AccountGroup.UUID uuid) {
    checkUUID(uuid);
    return uuid.get().substring(UUID_PREFIX.length());
  }

  private static AccountGroup.UUID uuid(String username) {
    return new AccountGroup.UUID(UUID_PREFIX + username);
  }

  private static void checkUUID(AccountGroup.UUID uuid) {
    checkArgument(
      uuid.get().startsWith(UUID_PREFIX),
      "SingleUserGroup does not handle %s", uuid.get());
  }

  private static String nameOf(AccountState account) {
    if (account.getAccount().getFullName() != null) {
      return account.getAccount().getFullName();
    } else if (account.getUserName() != null) {
      return NAME_PREFIX + account.getUserName();
    } else {
      return NAME_PREFIX + account.getAccount().getId().get();
    }
  }
}
