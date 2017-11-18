/**
 * Copyright (c) 2009--2015 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.manager.system;

import com.redhat.rhn.common.client.ClientCertificate;
import com.redhat.rhn.common.client.InvalidCertificateException;
import com.redhat.rhn.common.conf.Config;
import com.redhat.rhn.common.conf.ConfigDefaults;
import com.redhat.rhn.common.db.datasource.CallableMode;
import com.redhat.rhn.common.db.datasource.DataResult;
import com.redhat.rhn.common.db.datasource.ModeFactory;
import com.redhat.rhn.common.db.datasource.SelectMode;
import com.redhat.rhn.common.db.datasource.WriteMode;
import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.common.hibernate.LookupException;
import com.redhat.rhn.common.localization.LocalizationService;
import com.redhat.rhn.common.security.PermissionException;
import com.redhat.rhn.common.validator.ValidatorError;
import com.redhat.rhn.common.validator.ValidatorResult;
import com.redhat.rhn.common.validator.ValidatorWarning;
import com.redhat.rhn.domain.channel.AccessTokenFactory;
import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.channel.ChannelArch;
import com.redhat.rhn.domain.channel.ChannelFactory;
import com.redhat.rhn.domain.channel.ChannelFamily;
import com.redhat.rhn.domain.entitlement.Entitlement;
import com.redhat.rhn.domain.errata.Errata;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.rhnpackage.PackageFactory;
import com.redhat.rhn.domain.role.RoleFactory;
import com.redhat.rhn.domain.server.CPU;
import com.redhat.rhn.domain.server.InstalledPackage;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.Note;
import com.redhat.rhn.domain.server.ProxyInfo;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerArch;
import com.redhat.rhn.domain.server.ServerConstants;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.server.ServerGroup;
import com.redhat.rhn.domain.server.ServerLock;
import com.redhat.rhn.domain.server.VirtualInstance;
import com.redhat.rhn.domain.server.VirtualInstanceFactory;
import com.redhat.rhn.domain.server.VirtualInstanceState;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.domain.user.UserFactory;
import com.redhat.rhn.frontend.dto.ActivationKeyDto;
import com.redhat.rhn.frontend.dto.CustomDataKeyOverview;
import com.redhat.rhn.frontend.dto.ErrataOverview;
import com.redhat.rhn.frontend.dto.EssentialServerDto;
import com.redhat.rhn.frontend.dto.HardwareDeviceDto;
import com.redhat.rhn.frontend.dto.NetworkDto;
import com.redhat.rhn.frontend.dto.OrgProxyServer;
import com.redhat.rhn.frontend.dto.PackageListItem;
import com.redhat.rhn.frontend.dto.ServerPath;
import com.redhat.rhn.frontend.dto.SnapshotTagDto;
import com.redhat.rhn.frontend.dto.SystemCurrency;
import com.redhat.rhn.frontend.dto.SystemEventDto;
import com.redhat.rhn.frontend.dto.SystemGroupOverview;
import com.redhat.rhn.frontend.dto.SystemOverview;
import com.redhat.rhn.frontend.dto.SystemPendingEventDto;
import com.redhat.rhn.frontend.dto.VirtualSystemOverview;
import com.redhat.rhn.frontend.dto.kickstart.KickstartSessionDto;
import com.redhat.rhn.frontend.listview.PageControl;
import com.redhat.rhn.frontend.xmlrpc.InvalidProxyVersionException;
import com.redhat.rhn.frontend.xmlrpc.ProxySystemIsSatelliteException;
import com.redhat.rhn.manager.BaseManager;
import com.redhat.rhn.manager.action.ActionManager;
import com.redhat.rhn.manager.channel.ChannelManager;
import com.redhat.rhn.manager.channel.MultipleChannelsWithPackageException;
import com.redhat.rhn.manager.entitlement.EntitlementManager;
import com.redhat.rhn.manager.errata.ErrataManager;
import com.redhat.rhn.manager.kickstart.cobbler.CobblerSystemRemoveCommand;
import com.redhat.rhn.manager.rhnset.RhnSetDecl;
import com.redhat.rhn.manager.user.UserManager;
import com.redhat.rhn.taskomatic.TaskomaticApiException;

import com.suse.manager.webui.controllers.utils.ContactMethodUtil;
import com.suse.manager.webui.services.SaltStateGeneratorService;
import com.suse.manager.webui.services.impl.SaltService;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.log4j.Logger;
import org.hibernate.Session;

import java.net.IDN;
import java.sql.Date;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * SystemManager
 */
public class SystemManager extends BaseManager {

    private static Logger log = Logger.getLogger(SystemManager.class);
    private static SaltService saltServiceInstance = SaltService.INSTANCE;

    public static final String CAP_CONFIGFILES_UPLOAD = "configfiles.upload";
    public static final String CAP_CONFIGFILES_DIFF = "configfiles.diff";
    public static final String CAP_CONFIGFILES_MTIME_UPLOAD =
            "configfiles.mtime_upload";
    public static final String CAP_CONFIGFILES_DEPLOY = "configfiles.deploy";
    public static final String CAP_PACKAGES_VERIFY = "packages.verify";
    public static final String CAP_CONFIGFILES_BASE64_ENC =
            "configfiles.base64_enc";
    public static final String CAP_SCRIPT_RUN = "script.run";
    public static final String CAP_SCAP = "scap.xccdf_eval";

    private SystemManager() {
    }

    /**
     * Used in tests to mock the SaltService.
     * @param mockedSaltService The mocked SaltService.
     */
    public static void mockSaltService(SaltService mockedSaltService) {
        saltServiceInstance = mockedSaltService;
    }

    /**
     * Takes a snapshot for a server by calling the snapshot_server stored proc.
     * @param server The server to snapshot
     * @param reason The reason for the snapshotting.
     */
    public static void snapshotServer(Server server, String reason) {

        if (!Config.get().getBoolean(ConfigDefaults.TAKE_SNAPSHOTS)) {
            return;
        }

        // If the server is null or doesn't have the snapshotting feature, don't bother.
        if (server == null || !serverHasFeature(server.getId(), "ftr_snapshotting")) {
            return;
        }

        CallableMode m = ModeFactory.getCallableMode("System_queries", "snapshot_server");
        Map<String, Object> in = new HashMap<String, Object>();
        in.put("server_id", server.getId());
        in.put("reason", reason);
        m.execute(in, new HashMap<String, Integer>());
    }

    /**
     * Gets the list of channels that this server could subscribe to given it's base
     * channel.
     * @param sid The id of the server in question
     * @param uid The id of the user asking
     * @param cid The id of the base channel for the server
     * @return Returns a list of subscribable (child) channels for this server.
     */
    public static DataResult<Map<String, Object>> subscribableChannels(Long sid, Long uid,
            Long cid) {
        SelectMode m = ModeFactory.getMode("Channel_queries",
                "subscribable_channels", Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("server_id", sid);
        params.put("user_id", uid);
        params.put("base_channel_id", cid);

        return m.execute(params);
    }

    /**
     * Gets the list of channel ids that this server could subscribe to
     * according to it's base channel.
     * @param sid The id of the server in question
     * @param uid The id of the user asking
     * @param cid The id of the base channel for the server
     * @return Returns a list of subscribable (child) channel ids for this server.
     */
    public static Set<Long> subscribableChannelIds(Long sid, Long uid, Long cid) {
        Iterator<Map<String, Object>> subscribableChannelIter =
                subscribableChannels(sid, uid, cid).iterator();

        Set<Long> subscribableChannelIdSet = new HashSet<Long>();
        while (subscribableChannelIter.hasNext()) {
            Map<String, Object> row = subscribableChannelIter.next();
            subscribableChannelIdSet.add((Long) row.get("id"));
        }
        return subscribableChannelIdSet;
    }

    /**
     * Gets the list of channels that this server is subscribed to
     * @param sid The id of the server in question
     * @return Returns a list of subscribed channels for this server.
     */
    public static DataResult<Map<String, Object>> systemChannelSubscriptions(Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "system_channel_subscriptions");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        return m.execute(params);
    }

    /**
     * @param user
     *            Currently logged in user.
     * @param sid
     *            System id
     * @return true if the system requires a reboot i.e: because kernel updates.
     */
    public static boolean requiresReboot(User user, Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "has_errata_with_keyword_applied_since_last_reboot");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("sid", sid);
        params.put("keyword", "reboot_suggested");
        DataResult dr = m.execute(params);
        return !dr.isEmpty();
    }

    /**
     * Returns list of systems requiring a reboot i.e: because kernel updates,
     * visible to user, sorted by name.
     *
     * @param user
     *            Currently logged in user.
     * @param pc
     *            PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> requiringRebootList(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "having_errata_with_keyword_applied_since_last_reboot");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        params.put("keyword", "reboot_suggested");
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns a list of systems with extra packages installed.
     *
     * @param user User to check the systems for
     * @param pc Page control
     *
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> getExtraPackagesSystems(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries",
            "extra_packages_systems_count");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("userid", user.getId());
        params.put("orgid", user.getOrg().getId());

        return makeDataResult(params, new HashMap<String, Object>(), pc, m,
                SystemOverview.class);
    }

    /**
     * Returns the list of extra packages for a system.
     * @param serverId Server ID in question
     * @return List of extra packages
     */
    public static DataResult<PackageListItem> listExtraPackages(Long serverId) {
        SelectMode m = ModeFactory.getMode("Package_queries",
                                           "extra_packages_for_system");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("serverid", serverId);
        Map<String, Object> elabParams = new HashMap<String, Object>();

        return makeDataResult(params, elabParams, null, m, PackageListItem.class);
    }

    /**
     * Gets the latest upgradable packages for a system
     * @param sid The id for the system we want packages for
     * @return Returns a list of the latest upgradable packages for a system
     */
    public static DataResult<Map<String, Object>> latestUpgradablePackages(Long sid) {
        SelectMode m = ModeFactory.getMode("Package_queries",
                "system_upgradable_package_list_no_errata_info",
                Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        return m.execute(params);
    }

    /**
     * Get all installable packages for a given system.
     * @param sid The id for the system we want packages for
     * @return Return a list of all installable packages for a system.
     */
    public static DataResult<Map<String, Object>> allInstallablePackages(Long sid) {
        SelectMode m = ModeFactory.getMode("Package_queries",
                "system_all_available_packages",
                Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        return m.execute(params);
    }

    /**
     * Gets the latest installable packages for a system
     * @param sid The id for the system we want packages for
     * @return Returns a list of latest installable packages for a system.
     */
    public static DataResult<Map<String, Object>> latestInstallablePackages(Long sid) {
        SelectMode m = ModeFactory.getMode("Package_queries",
                "system_latest_available_packages",
                Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        return m.execute(params);
    }

    /**
     * Gets the installed packages on a system
     * @param sid The system in question
     * @param expanded If true, also adds EVR, Arch and package name to the result.
     * @return Returns a list of packages for a system
     */
    public static DataResult<Map<String, Object>> installedPackages(Long sid,
            boolean expanded) {
        String suffix = expanded ? "_expanded" : "";
        SelectMode m = ModeFactory.getMode("System_queries",
                                           "system_installed_packages" + suffix,
                                           Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        DataResult<Map<String, Object>> pkgs = m.execute(params);
        for (Map<String, Object> pkg : pkgs) {
            if (pkg.get("arch") == null) {
                pkg.put("arch", LocalizationService.getInstance().getMessage("Unknown"));
            }
            if (pkg.get("installtime") == null) {
                pkg.remove("installtime");
            }
        }
        return pkgs;
    }

    /**
     * Gets packages from a channel for a system
     * @param sid server id
     * @param cid channel id
     * @return list of packages installed on a system from a channel
     */
    public static DataResult<Map<String, Object>> packagesFromChannel(Long sid, Long cid) {
        SelectMode m = ModeFactory.getMode("Package_queries",
                "system_packages_from_channel", Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("cid", cid);
        return m.execute(params);
    }

    /**
     * How to cleanup the server on deletion.
      */
    public enum ServerCleanupType {
        /**
         * Fail in case of cleanup error.
         */
        FAIL_ON_CLEANUP_ERR,
        /**
         * Don't cleanup, just delete.
         */
        NO_CLEANUP,
        /**
         * Try cleanup first but delete server
         * anyway in case of error.
         */
        FORCE_DELETE;

        /**
         * Get enum value from string.
         * @param value the string
         * @return an Optional with the enum value or empty if string didn't
         * match any enum value.
         */
        public static Optional<ServerCleanupType> fromString(String value) {
            try {
                return Optional.of(valueOf(value.toUpperCase()));
            }
            catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

    }

    /**
     * Delete a server and in case of Salt ssh-push minions remove SUSE Manager
     * specific configuration. When removing ssh-push minions the default
     * timeout for the cleanup operation is set to 5 minutes.
     *
     * @param user the user
     * @param sid the server id
     * @param cleanupType cleanup options
     * @return a list of cleanup errors or empty if no errors or no cleanup was done
     */
    public static Optional<List<String>> deleteServerAndCleanup(
            User user, long sid, ServerCleanupType cleanupType) {
        return deleteServerAndCleanup(user, sid, cleanupType, 300);
    }

    /**
     * Delete a server and in case of Salt ssh-push minions remove SUSE Manager
     * specific configuration.
     *
     * @param user the user
     * @param sid the server id
     * @param cleanupType cleanup options
     * @param cleanupTimeout timeout for cleanup operation
     * @return a list of cleanup errors or empty if no errors or no cleanup was done
     */
    public static Optional<List<String>> deleteServerAndCleanup(
            User user, long sid, ServerCleanupType cleanupType, int cleanupTimeout) {
        if (!ServerCleanupType.NO_CLEANUP.equals(cleanupType)) {
            Server server = lookupByIdAndUser(sid, user);
            if (server.asMinionServer().isPresent()) {
                boolean sshPush = Stream.of(
                        ServerFactory.findContactMethodByLabel(ContactMethodUtil.SSH_PUSH),
                        ServerFactory
                                .findContactMethodByLabel(ContactMethodUtil.SSH_PUSH_TUNNEL)
                ).anyMatch(cm -> server.getContactMethod().equals(cm));
                if (sshPush) {
                    Optional<List<String>> errs = saltServiceInstance
                            .cleanupSSHMinion(server.asMinionServer().get(),
                                    cleanupTimeout);
                    if (errs.isPresent() &&
                            ServerCleanupType.FAIL_ON_CLEANUP_ERR.equals(cleanupType)) {
                        return errs;
                    } // else FORCE_DELETE
                }
            }
        }
        deleteServer(user, sid);
        return Optional.empty();
    }

    /**
     * Deletes a Server and associated VirtualInstances:
     *  - If the server was a virtual guest, remove the VirtualInstance that links it to its
     *  host server.
     *  - If the server was a virtual host, remove all its entitlements and all
     *  VirtualInstances that link it to the guest servers.
     * @param user The user doing the deleting.
     * @param sid The id of the Server to be deleted
     */
    public static void deleteServer(User user, Long sid) {
        /*
         * Looking up the server here rather than being passed in a Server object, allows
         * us to call lookupByIdAndUser which will ensure the user has access to this
         * server.
         */
        Server server = lookupByIdAndUser(sid, user);

        CobblerSystemRemoveCommand rc = new CobblerSystemRemoveCommand(user, server);
        rc.store();

        // remove associated VirtualInstances
        Set<VirtualInstance> toRemove = new HashSet<>();
        if (server.isVirtualGuest()) {
            toRemove.add(server.getVirtualInstance());
        }
        else {
            removeAllServerEntitlements(server.getId());
            toRemove.addAll(server.getGuests());
        }
        toRemove.stream().forEach(vi ->
            VirtualInstanceFactory.getInstance().deleteVirtualInstanceOnly(vi));


        server.asMinionServer().ifPresent(minion -> {
            minion.getAccessTokens().forEach(token -> {
                token.setValid(false);
                AccessTokenFactory.save(token);
            });
        });

        // remove server itself
        ServerFactory.delete(server);

        server.asMinionServer().ifPresent(minion -> {
            saltServiceInstance.deleteKey(minion.getMinionId());
            SaltStateGeneratorService.INSTANCE.removeServer(minion);
        });
    }

    /**
     * Adds a server to a server group
     * @param server The server to add
     * @param serverGroup The group to add the server to
     */
    public static void addServerToServerGroup(Server server, ServerGroup serverGroup) {
        ServerFactory.addServerToGroup(server, serverGroup);
        snapshotServer(server, "Group membership alteration");
    }

    /**
     * Removes a server from a group
     * @param server The server to remove
     * @param serverGroup The group to remove the server from
     */
    public static void removeServerFromServerGroup(Server server, ServerGroup serverGroup) {
        ServerFactory.removeServerFromGroup(server.getId(), serverGroup.getId());
        snapshotServer(server, "Group membership alteration");
    }

    /**
     * Returns a list of available server groups for a given server
     * @param server The server in question
     * @param user The user requesting the information
     * @return Returns a list of system groups available for this server/user
     */
    public static DataResult<Map<String, Object>> availableSystemGroups(Server server,
            User user) {
        SelectMode m = ModeFactory.getMode("SystemGroup_queries", "visible_to_system",
                Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", server.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        return m.execute(params);
    }

    /**
     * Returns a list of server groups for a given server
     * @param sid The server id in question
     * @return Returns a list of system groups for this server
     */
    public static DataResult<Map<String, Object>> listSystemGroups(Long sid) {
        SelectMode m = ModeFactory.getMode("SystemGroup_queries",
                                           "groups_a_system_is_in_unsafe", Map.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        return m.execute(params);
    }

    /**
     * Returns list of all notes for a system.
     * @param s The server in question
     * @return list of SystemNotes.
     */
    public static DataResult<Map<String, Object>> systemNotes(Server s) {
        SelectMode m = ModeFactory.getMode("System_queries", "server_notes");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", s.getId());
        return m.execute(params);
    }

    /**
     * Returns list of all systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "visible_to_user");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of all physical systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> physicalList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "visible_to_user_physical_list");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of all systems and their errata type counts
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemCurrency> systemCurrencyList(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "system_currency");
        Map<String, Object> params = new HashMap<>();
        params.put("uid", user.getId());

        return m.execute(params, Arrays.asList(
                ServerConstants.getServerGroupTypeForeignEntitled().getId()));
    }

    /**
     * Returns list of all systems visible to user.
     *    This is meant to be fast and only gets the id, name, and last checkin
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemListShort(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "xmlrpc_visible_to_user",
                SystemOverview.class);
        Map<String, Long> params = new HashMap<String, Long>();
        params.put("user_id", user.getId());
        Map<String, Long> elabParams = new HashMap<String, Long>();

        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of all systems visible to user that are inactive.
     *    This is meant to be fast and only gets the id, name, and last checkin
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemListShortInactive(User user,
            PageControl pc) {
        return systemListShortInactive(user, new Integer(Config.get().getInt(ConfigDefaults
                .SYSTEM_CHECKIN_THRESHOLD)), pc);
    }

    /**
     * Returns list of all systems visible to user that are inactive.
     *    This is meant to be fast and only gets the id, name, and last checkin
     * @param user Currently logged in user.
     * @param inactiveThreshold number of days before we consider systems inactive
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemListShortInactive(
            User user, int inactiveThreshold, PageControl pc) {
        SelectMode m = ModeFactory.getMode(
                "System_queries", "xmlrpc_visible_to_user_inactive",
                SystemOverview.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("checkin_threshold", inactiveThreshold);
        Map<String, Object> elabParams = new HashMap<String, Object>();

        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of all systems visible to user that are active.
     *    This is meant to be fast and only gets the id, name, and last checkin
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemListShortActive(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode(
                "System_queries", "xmlrpc_visible_to_user_active", SystemOverview.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("checkin_threshold", new Integer(Config.get().getInt(ConfigDefaults
                .SYSTEM_CHECKIN_THRESHOLD)));
        Map<String, Object> elabParams = new HashMap<String, Object>();

        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of all systems that are  visible to user
     * but not in the given server group.
     * @param user Currently logged in user.
     * @param sg a ServerGroup
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemsNotInGroup(User user,
            ServerGroup sg, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "target_systems_for_group");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sgid", sg.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns a list of all systems visible to user with pending errata.
     * @param user Current logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews
     */
    public static DataResult<SystemOverview> mostCriticalSystems(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "most_critical_systems");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of all systems visible to user.
     * @param user Currently logged in user.
     * @param feature The String label of the feature we want to get a list of systems for.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemsWithFeature(User user, String feature,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "systems_with_feature");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("feature", feature);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of out of date systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> outOfDateList(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "out_of_date");
        Map<String, Long> params = new HashMap<String, Long>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        Map<String, Long> elabParams = new HashMap<String, Long>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of unentitled systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> unentitledList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "unentitled");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of ungrouped systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> ungroupedList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "ungrouped");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of inactive systems visible to user, sorted by name.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> inactiveList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "inactive");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        params.put("checkin_threshold", new Integer(Config.get().getInt(ConfigDefaults
                .SYSTEM_CHECKIN_THRESHOLD)));
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of inactive systems visible to user, sorted by name.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @param inactiveDays number of days the systems should have been inactive for
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> inactiveList(User user, PageControl pc,
            int inactiveDays) {
        SelectMode m = ModeFactory.getMode("System_queries", "inactive");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        params.put("checkin_threshold", inactiveDays);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }


    /**
     * Returns a list of systems recently registered by the user
     * @param user Currently logged in user.
     * @param pc PageControl
     * @param threshold maximum amount of days ago the system, 0 returns all systems
     * was registered for it to appear in the list
     * @return list of SystemOverviews
     */
    public static DataResult<SystemOverview> registeredList(User user,
            PageControl pc,
            int threshold) {
        SelectMode m;
        Map<String, Object> params = new HashMap<String, Object>();

        if (threshold == 0) {
            m = ModeFactory.getMode("System_queries",
                    "all_systems_by_registration");
        }
        else {
            m = ModeFactory.getMode("System_queries",
                    "recently_registered");
            params.put("threshold", new Integer(threshold));
        }

        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of inactive systems visible to user, sorted by the systems' last
     * checkin time instead of by name.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> inactiveListSortbyCheckinTime(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "inactive_order_by_checkin_time");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        params.put("checkin_threshold", new Integer(Config.get().getInt(ConfigDefaults
                .SYSTEM_CHECKIN_THRESHOLD)));
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of proxy systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> proxyList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "proxy_servers");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of virtual host systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<VirtualSystemOverview> virtualSystemsList(
            User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "virtual_servers");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, VirtualSystemOverview.class);
    }

    /**
     * Returns list of virtual guest systems running 'under' the given system.
     * @param user Currently logged in user.
     * @param sid The id of the system we are looking at
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<VirtualSystemOverview> virtualGuestsForHostList(
            User user, Long sid, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "virtual_guests_for_host");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, VirtualSystemOverview.class);
    }

    /**
     * Returns list of virtual systems in the given set
     * @param user Currently logged in user.
     * @param setLabel The label of the set of virtual systems
     *        (rhnSet.elem = rhnVirtualInstance.id)
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<VirtualSystemOverview> virtualSystemsInSet(
            User user,
            String setLabel,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "virtual_systems_in_set");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, VirtualSystemOverview.class);
    }

    /**
     * Returns list of system groups visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemGroupOverviews.
     */
    public static DataResult<SystemGroupOverview> groupList(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("SystemGroup_queries", "visible_to_user");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        Map<String, Object> elabParams = new HashMap<String, Object>();
        elabParams.put("org_id", user.getOrg().getId());
        elabParams.put("user_id", user.getId());
        return makeDataResult(params, elabParams, pc, m, SystemGroupOverview.class);
    }

    /**
     * Returns list of systems in the specified group.
     * @param sgid System Group Id
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemsInGroup(Long sgid,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "systems_in_group");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sgid", sgid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemOverview.class);
    }

    /**
     * Returns list of systems in the specified group.
     * This is meant to be fast and only return id, name, and last_checkin
     * @param sgid System Group Id
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> systemsInGroupShort(Long sgid) {
        SelectMode m = ModeFactory.getMode("System_queries", "xmlrpc_systems_in_group");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sgid", sgid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, null, m, SystemOverview.class);
    }

    /**
     * Returns the number of actions associated with a system
     * @param sid The system's id
     * @return number of actions
     */
    public static int countActions(Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "actions_count");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("server_id", sid);
        DataResult<Map<String, Object>> dr = makeDataResult(params, params, null, m);
        return ((Long) dr.get(0).get("count")).intValue();
    }

    /**
     * Returns the number of package actions associated with a system
     * @param sid The system's id
     * @return number of package actions
     */
    public static int countPackageActions(Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "package_actions_count");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("server_id", sid);
        DataResult<Map<String, Object>> dr = makeDataResult(params, params, null, m);
        return ((Long) dr.get(0).get("count")).intValue();
    }

    /**
     * Returns a list of unscheduled relevent errata for a system
     * @param user The user
     * @param sid The system's id
     * @param pc PageControl
     * @return a list of ErrataOverviews
     */
    public static DataResult<Errata> unscheduledErrata(User user, Long sid,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("Errata_queries",
                "unscheduled_relevant_to_system");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);

        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, Errata.class);
    }

    /**
     * Returns whether a system has unscheduled relevant errata
     * @param user The user
     * @param sid The system's id
     * @return boolean of if system has unscheduled errata
     */
    public static boolean hasUnscheduledErrata(User user, Long sid) {
        SelectMode m = ModeFactory.getMode("Errata_queries",
                "count_unscheduled_relevant_to_system");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);
        DataResult<Map<String, Object>> dr = makeDataResult(params, null, null, m);
        return ((Long) dr.get(0).get("count")).intValue() > 0;
    }

    /**
     * Returns Kickstart sessions associated with a server
     * @param user The logged in user
     * @param sid The server id
     * @return a list of KickStartSessions
     */
    public static DataResult<KickstartSessionDto>
            lookupKickstartSession(User user, Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "lookup_kickstart");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", user.getOrg().getId());
        params.put("sid", sid);

        return makeDataResult(params, params, null, m, KickstartSessionDto.class);
    }

    /**
     * Returns whether or not a server is kickstarting
     * @param user The logged in user
     * @param sid The server id
     * @return boolean of if a server is kickstarting
     */
    public static boolean isKickstarting(User user, Long sid) {
        Iterator<KickstartSessionDto> i = lookupKickstartSession(user, sid).iterator();
        while (i.hasNext()) {
            KickstartSessionDto next = i.next();
            if (!(next.getState().equals("complete") ||
                    next.getState().equals("failed"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of errata relevant to a system
     * @param user The user
     * @param sid System Id
     * @return a list of ErrataOverviews
     */
    public static DataResult<ErrataOverview> relevantErrata(User user, Long sid) {
        SelectMode m = ModeFactory.getMode("Errata_queries", "relevant_to_system");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);

        Map<String, Object> elabParams = new HashMap<String, Object>();
        elabParams.put("sid", sid);
        elabParams.put("user_id", user.getId());

        return makeDataResultNoPagination(params, elabParams, m, ErrataOverview.class);
    }

    /**
     * Returns a list of errata relevant to a system
     * @param user The user
     * @param sid System Id
     * @param types of errata types (strings) to include
     * @return a list of ErrataOverviews
     */
    public static DataResult<ErrataOverview> relevantErrata(User user,
            Long sid, List<String> types) {
        SelectMode m = ModeFactory.getMode("Errata_queries", "relevant_to_system_by_types");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);

        Map<String, Object> elabParams = new HashMap<String, Object>();
        elabParams.put("sid", sid);
        elabParams.put("user_id", user.getId());

        DataResult<ErrataOverview> dr =  m.execute(params, types);
        dr.setElaborationParams(elabParams);
        return dr;
    }

    /**
     * Returns a list of errata relevant to a system
     * @param user The user
     * @param sid System Id
     * @param type of errata to include
     * @param severityLabel to filter by
     * @return a list of ErrataOverviews
     */
    public static DataResult<ErrataOverview> relevantCurrencyErrata(User user,
            Long sid, String type, String severityLabel) {
        SelectMode m = ModeFactory.getMode("Errata_queries",
                "security_relevant_to_system_by_severity");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);
        params.put("type", type);
        params.put("severity_label", severityLabel);

        Map<String, Object> elabParams = new HashMap<String, Object>();
        elabParams.put("sid", sid);
        elabParams.put("user_id", user.getId());

        DataResult<ErrataOverview> dr =  m.execute(params);
        dr.setElaborationParams(elabParams);
        return dr;
    }

    /**
     * Returns a list of errata relevant to a system by type
     * @param user The user
     * @param sid System Id
     * @param type Type
     * @return a list of ErrataOverviews
     */
    public static DataResult<ErrataOverview> relevantErrataByType(User user, Long sid,
            String type) {
        SelectMode m = ModeFactory.getMode("Errata_queries", "relevant_to_system_by_type");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);
        params.put("type", type);

        Map<String, Object> elabParams = new HashMap<String, Object>();
        elabParams.put("sid", sid);
        elabParams.put("user_id", user.getId());

        return makeDataResultNoPagination(params, elabParams, m, ErrataOverview.class);
    }

    /**
     * Returns a count of the number of critical errata that are present on the system.
     *
     * @param user user making the request
     * @param sid  identifies the server
     * @return number of critical errata on the system
     */
    public static int countCriticalErrataForSystem(User user, Long sid) {
        SelectMode m = ModeFactory.getMode("Errata_queries",
                "count_critical_errata_for_system");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);

        DataResult<Map<String, Object>> dr = makeDataResult(params, null, null, m);
        return ((Long) dr.get(0).get("count")).intValue();
    }

    /**
     * Returns a count of the number of non-critical errata that are present on the system.
     *
     * @param user user making the request
     * @param sid  identifies the server
     * @return number of non-critical errata on the system
     */
    public static int countNoncriticalErrataForSystem(User user, Long sid) {
        SelectMode m = ModeFactory.getMode("Errata_queries",
                "count_noncritical_errata_for_system");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("sid", sid);

        DataResult<Map<String, Object>> dr = makeDataResult(params, null, null, m);
        return ((Long) dr.get(0).get("count")).intValue();
    }

    /**
     * Returns a list of errata in a specified set
     * @param user The user
     * @param label The label for the errata set
     * @param pc PageControl
     * @return a list of ErrataOverviews
     */
    public static DataResult errataInSet(User user, String label,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("Errata_queries", "in_set");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("set_label", label);

        Map<String, Object> elabParams = new HashMap<String, Object>();
        elabParams.put("user_id", user.getId());

        DataResult dr =  m.execute(params);
        dr.setElaborationParams(elabParams);
        return dr;
    }

    /**
     * Looks up a server by its Id
     * @param sid The server's id
     * @param userIn who wants to lookup the Server
     * @return a server object associated with the given Id
     */
    public static Server lookupByIdAndUser(Long sid, User userIn) {
        Server server = ServerFactory.lookupByIdAndOrg(sid,
                userIn.getOrg());
        ensureAvailableToUser(userIn, sid);
        return server;
    }


    /**
     * Returns a List of hydrated server objects from server ids.
     * @param serverIds the list of server ids to hyrdrate
     * @param userIn the user who wants to lookup the server
     * @return a List of hydrated server objects.
     */
    public static List<Server> hydrateServerFromIds(Collection<Long> serverIds,
            User userIn) {
        List<Server> servers = new ArrayList<Server>(serverIds.size());
        for (Long id : serverIds) {
            servers.add(lookupByIdAndUser(id, userIn));
        }
        return servers;
    }

    /**
     * Looks up a server by its Id
     * @param sid The server's id
     * @param org who wants to lookup the Server
     * @return a server object associated with the given Id
     */
    public static Server lookupByIdAndOrg(Long sid, Org org) {
        return ServerFactory.lookupByIdAndOrg(sid, org);
    }

    /**
     * Looks up a Server by it's client certificate.
     * @param cert ClientCertificate of the server.
     * @return the Server which matches the client certificate.
     * @throws InvalidCertificateException thrown if certificate is invalid.
     */
    public static Server lookupByCert(ClientCertificate cert)
            throws InvalidCertificateException {

        return ServerFactory.lookupByCert(cert);
    }

    /**
     * Returns the list of activation keys used when the system was
     * registered.
     * @param serverIn the server to query for
     * @return list of ActivationKeyDto containing the token id and name
     */
    public static DataResult<ActivationKeyDto> getActivationKeys(Server serverIn) {

        SelectMode m = ModeFactory.getMode("General_queries",
                "activation_keys_for_server");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("server_id", serverIn.getId());
        return makeDataResult(params, Collections.EMPTY_MAP, null, m,
                ActivationKeyDto.class);
    }

    /**
     * Returns list of inactive systems visible to user, sorted by name.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview>
            getSystemEntitlements(User user, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "system_entitlement_list");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        return makeDataResult(params, Collections.EMPTY_MAP, pc, m, SystemOverview.class);
    }



    /**
     * Returns the entitlements for the given server id.
     * @param sid Server id
     * @return entitlements - ArrayList of entitlements
     */
    public static List<Entitlement> getServerEntitlements(Long sid) {
        List<Entitlement> entitlements = new ArrayList<Entitlement>();

        SelectMode m = ModeFactory.getMode("General_queries", "system_entitlements");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);

        DataResult<Map<String, Object>> dr = makeDataResult(params, null, null, m);

        if (dr.isEmpty()) {
            return null;
        }

        Iterator<Map<String, Object>> iter = dr.iterator();
        while (iter.hasNext()) {
            Map<String, Object> map = iter.next();
            String ent = (String) map.get("label");
            entitlements.add(EntitlementManager.getByName(ent));
        }

        return entitlements;
    }

    /**
     * Used to test if the server has a specific entitlement.
     * We should almost always check for features with serverHasFeature instead.
     * @param sid Server id
     * @param ent Entitlement to look for
     * @return true if the server has the specified entitlement
     */
    public static boolean hasEntitlement(Long sid, Entitlement ent) {
        List<Entitlement> entitlements = getServerEntitlements(sid);

        return entitlements != null && entitlements.contains(ent);
    }

    /**
     * Used to test if the server has a specific feature.
     * We should almost always check for features with serverHasFeature instead.
     * @param sid Server id
     * @param feat Feature to look for
     * @return true if the server has the specified feature
     */
    public static boolean serverHasFeature(Long sid, String feat) {
        SelectMode m = ModeFactory.getMode("General_queries", "system_has_feature");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("feature", feat);

        DataResult dr = makeDataResult(params, null, null, m);
        return !dr.isEmpty();
    }

    /**
     * Return <code>true</code> the given server has virtualization entitlements,
     * <code>false</code> otherwise.

     * @param sid Server ID to lookup.
     * @param org Org id of user performing this query.
     * @return <code>true</code> if the server has virtualization entitlements,
     *      <code>false</code> otherwise.
     */
    public static boolean serverHasVirtuaizationEntitlement(Long sid, Org org) {
        Server s = SystemManager.lookupByIdAndOrg(sid, org);
        return s.hasVirtualizationEntitlement();
    }

    /**
     * Return <code>true</code> the given server has bootstrap entitlement,
     * <code>false</code> otherwise.

     * @param sid Server ID to lookup.
     * @return <code>true</code> if the server has bootstrap entitlement,
     *      <code>false</code> otherwise.
     */
    public static boolean serverHasBootstrapEntitlement(Long sid) {
        Server s = ServerFactory.lookupById(sid);
        return s.hasEntitlement(EntitlementManager.BOOTSTRAP);
    }

    /**
     * Returns a count of systems without
     * a certain set of entitlements in a set of systems.
     *
     * @param user user making the request
     * @param setLabel label of the set
     * @param entitlements list of entitlement labels
     * @return number of systems in the set without those entitlements
     */
    public static int countSystemsInSetWithoutEntitlement(User user, String setLabel,
        List<String> entitlements) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "count_systems_in_set_without_entitlement");

        Map params = new HashMap();
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        DataResult dr = m.execute(params, entitlements);
        return ((Long)((HashMap)dr.get(0)).get("count")).intValue();
    }

    /**
     * Returns a count of systems without a certain feature in a set.
     *
     * @param user user making the request
     * @param setLabel label of the set
     * @param featureLabel label of the feature
     * @return number of systems in the set without the feature
     */
    public static int countSystemsInSetWithoutFeature(User user, String setLabel,
        String featureLabel) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "count_systems_in_set_without_feature");

        Map params = new HashMap();
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        params.put("feature_label", featureLabel);

        DataResult dr = makeDataResult(params, null, null, m);
        return ((Long)((HashMap)dr.get(0)).get("count")).intValue();
    }

    /**
     * Returns true if server has capability.
     * @param sid Server id
     * @param capability capability
     * @return true if server has capability
     */
    public static boolean clientCapable(Long sid, String capability) {
        SelectMode m = ModeFactory.getMode("System_queries", "lookup_capability");

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("name", capability);

        DataResult dr = makeDataResult(params, params, null, m);
        return !dr.isEmpty();
    }

    /**
     * Returns a list of Servers which are compatible with the given server.
     * @param user User owner
     * @param server Server whose profiles we want.
     * @return  a list of Servers which are compatible with the given server.
     */
    public static List<Map<String, Object>> compatibleWithServer(User user, Server server) {
        return ServerFactory.compatibleWithServer(user, server);
    }

    /**
     * Subscribes the given server to the given channel.
     * @param user Current user
     * @param server Server to be subscribed
     * @param channel Channel to subscribe to.
     * @return the modified server if there were
     *           any changes modifications made
     *           to the Server during the call.
     *           Make sure the caller uses the
     *           returned server.
     */
    public static Server subscribeServerToChannel(User user,
            Server server, Channel channel) {
        return subscribeServerToChannel(user, server, channel, false);
    }

    /**
     * Subscribes the given server to the given channel.
     * @param user Current user
     * @param server Server to be subscribed
     * @param channel Channel to subscribe to.
     * @param flush flushes the hibernate session.
     * @return the modified server if there were
     *           any changes modifications made
     *           to the Server during the call.
     *           Make sure the caller uses the
     *           returned server.
     */
    public static Server subscribeServerToChannel(User user,
            Server server,
            Channel channel,
            boolean flush) {

        // do not allow non-satellite servers to be subscribed to satellite channels.
        if (channel.isSatellite()) {
            if (!server.isSatellite()) {
                return server;
            }
        }

        if (user != null && !ChannelManager.verifyChannelSubscribe(user, channel.getId())) {
            //Throw an exception with a nice error message so the user
            //knows what went wrong.
            LocalizationService ls = LocalizationService.getInstance();
            PermissionException pex = new PermissionException("User does not have" +
                    " permission to subscribe this server to this channel.");
            pex.setLocalizedTitle(ls.getMessage("permission.jsp.title.subscribechannel"));
            pex.setLocalizedSummary(
                    ls.getMessage("permission.jsp.summary.subscribechannel"));
            throw pex;
        }

        if (!verifyArchCompatibility(server, channel)) {
            throw new IncompatibleArchException(
                    server.getServerArch(), channel.getChannelArch());
        }

        log.debug("calling subscribe_server_to_channel");
        CallableMode m = ModeFactory.getCallableMode("Channel_queries",
                "subscribe_server_to_channel");

        Map<String, Object> in = new HashMap<String, Object>();
        in.put("server_id", server.getId());
        if (user != null) {
            in.put("user_id", user.getId());
        }
        else {
            in.put("user_id", null);
        }
        in.put("channel_id", channel.getId());

        m.execute(in, new HashMap<String, Integer>());

        /*
         * This is f-ing hokey, but we need to be sure to refresh the
         * server object since
         * we modified it outside of hibernate :-/
         * This will update the server.channels set.
         */
        log.debug("returning with a flush? " + flush);
        if (flush) {
            return (Server) HibernateFactory.reload(server);
        }
        HibernateFactory.getSession().refresh(server);
        return server;
    }

    /**
     * Returns true if the given server has a compatible architecture with the
     * given channel architecture. False if the server or channel is null or
     * they are not compatible.
     * @param server Server architecture to be verified.
     * @param channel Channel to check
     * @return true if compatible; false if null or not compatible.
     */
    public static boolean verifyArchCompatibility(Server server, Channel channel) {
        if (server == null || channel == null) {
            return false;
        }
        return channel.getChannelArch().isCompatible(server.getServerArch());
    }

    /**
     * Unsubscribe given server from the given channel.
     * @param user The user performing the operation
     * @param server The server to be unsubscribed
     * @param channel The channel to unsubscribe from
     */
    public static void unsubscribeServerFromChannel(User user, Server server,
            Channel channel) {
        unsubscribeServerFromChannel(user, server, channel, false);
    }

    /**
     * Unsubscribe given server from the given channel.
     * @param user The user performing the operation
     * @param sid The id of the server to be unsubscribed
     * @param cid The id of the channel from which the server will be unsubscribed
     */
    public static void unsubscribeServerFromChannel(User user, Long sid,
            Long cid) {
        if (ChannelManager.verifyChannelSubscribe(user, cid)) {
            unsubscribeServerFromChannel(sid, cid);
        }
    }

    /**
     * Unsubscribe given server from the given channel.
     * @param user The user performing the operation
     * @param server The server to be unsubscribed
     * @param channel The channel to unsubscribe from
     * @param flush flushes the hibernate session. Make sure you
     *              reload the server and channel after  method call
     *              if you set this to true..
     */
    public static void unsubscribeServerFromChannel(User user, Server server,
            Channel channel, boolean flush) {
        if (!isAvailableToUser(user, server.getId())) {
            //Throw an exception with a nice error message so the user
            //knows what went wrong.
            LocalizationService ls = LocalizationService.getInstance();
            PermissionException pex = new PermissionException("User does not have" +
                    " permission to unsubscribe this server from this channel.");
            pex.setLocalizedTitle(ls.getMessage("permission.jsp.title.subscribechannel"));
            pex.setLocalizedSummary(
                    ls.getMessage("permission.jsp.summary.subscribechannel"));
            throw pex;
        }

        unsubscribeServerFromChannel(server, channel, flush);
    }

    /**
     * Unsubscribe given server from the given channel. If you use this method,
     * YOU BETTER KNOW WHAT YOU'RE DOING!!! (Use the version that takes a user as well if
     * you're unsure. better safe than sorry).
     * @param server server to be unsubscribed
     * @param channel the channel to unsubscribe from
     * @return the modified server if there were
     *           any changes modifications made
     *           to the Server during the call.
     *           Make sure the caller uses the
     *           returned server.
     */
    public static Server unsubscribeServerFromChannel(Server server,
            Channel channel) {
        return unsubscribeServerFromChannel(server, channel, false);
    }

    /**
     * Unsubscribe given server from the given channel. If you use this method,
     * YOU BETTER KNOW WHAT YOU'RE DOING!!! (Use the version that takes a user as well if
     * you're unsure. better safe than sorry).
     * @param server server to be unsubscribed
     * @param channel the channel to unsubscribe from
     * @param flush flushes the hibernate session. Make sure you
     *              reload the server and channel after  method call
     *              if you set this to true..
     * @return the modified server if there were
     *           any changes modifications made
     *           to the Server during the call.
     *           Make sure the caller uses the
     *           returned server.
     */
    public static Server unsubscribeServerFromChannel(Server server,
            Channel channel,
            boolean flush) {
        if (channel == null) {
            //nothing to do ;)
            return server;
        }

        unsubscribeServerFromChannel(server.getId(), channel.getId());

        /*
         * This is f-ing hokey, but we need to be sure to refresh the
         * server object since we modified it outside of hibernate :-/
         * This will update the server.channels set.
         */
        if (flush) {
            return (Server)HibernateFactory.reload(server);
        }
        HibernateFactory.getSession().refresh(server);
        return server;
    }

    /**
     * Unsubscribes a server from a channel without any check. Please use other
     * overloaded versions of this method if unsure.
     * @param sid the server id
     * @param cid the channel id
     */
    public static void unsubscribeServerFromChannel(Long sid, Long cid) {
        CallableMode m = ModeFactory.getCallableMode("Channel_queries",
            "unsubscribe_server_from_channel");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("server_id", sid);
        params.put("channel_id", cid);
        m.execute(params, new HashMap<String, Integer>());
    }

    /**
     * Deactivates the given proxy.
     * Make sure you either reload  the server after this call,,
     * or use the returned Server object
     * @param server ProxyServer to be deactivated.
     * @return deproxified server.
     */
    public static Server deactivateProxy(Server server) {
        ServerFactory.deproxify(server);

        // Unsubscribe only if we are configured to automatically re-subscribe again,
        // see the activateProxy() method
        if (Config.get().getBoolean(ConfigDefaults.WEB_SUBSCRIBE_PROXY_CHANNEL)) {
            Set<Channel> channels = server.getChannels();
            for (Iterator<Channel> itr = channels.iterator(); itr.hasNext();) {
                Channel c = itr.next();
                ChannelFamily cf = c.getChannelFamily();
                if (cf.getLabel().equals("SMP")) {
                    SystemManager.unsubscribeServerFromChannel(server, c);
                }
            }
        }

        return server;
    }

    private static int executeWriteMode(String catalog, String mode,
            Map<String, Object> params) {
        WriteMode m = ModeFactory.getWriteMode(catalog, mode);
        return m.executeUpdate(params);
    }

    /**
     * Creates the client certificate (systemid) file for the given Server.
     * @param server Server whose client certificate is sought.
     * @return the client certificate (systemid) file for the given Server.
     * @throws InstantiationException thrown if error occurs creating the
     * client certificate.
     */
    public static ClientCertificate createClientCertificate(Server server)
            throws InstantiationException {

        ClientCertificate cert = new ClientCertificate();
        // add members to this cert
        User user = UserManager.findResponsibleUser(server.getOrg(), RoleFactory.ORG_ADMIN);
        cert.addMember("username", user.getLogin());
        cert.addMember("os_release", server.getRelease());
        cert.addMember("operating_system", server.getOs());
        cert.addMember("architecture",  server.getServerArch().getLabel());
        cert.addMember("system_id", "ID-" + server.getId().toString());
        cert.addMember("type", "REAL");
        String[] fields = {"system_id", "os_release", "operating_system",
                "architecture", "username", "type"};
        cert.addMember("fields", fields);

        try {
            //Could throw InvalidCertificateException in any fields are invalid
            cert.addMember("checksum", cert.genSignature(server.getSecret()));
        }
        catch (InvalidCertificateException e) {
            throw new InstantiationException("Couldn't generate signature");
        }

        return cert;
    }

    /**
     * Store the server back to the db
     * @param serverIn The server to save
     */
    public static void storeServer(Server serverIn) {
        ServerFactory.save(serverIn);
    }

    /**
     * Activates the given proxy for the given version.
     * @param server proxy server to activate.
     * @param version Proxy version.
     * @throws ProxySystemIsSatelliteException thrown if system is a satellite.
     * @throws InvalidProxyVersionException thrown if version is invalid.
     */
    public static void activateProxy(Server server, String version)
            throws ProxySystemIsSatelliteException, InvalidProxyVersionException {

        if (server.isSatellite()) {
            throw new ProxySystemIsSatelliteException();
        }

        ProxyInfo info = new ProxyInfo();
        info.setServer(server);
        info.setVersion(null, version, "1");
        server.setProxyInfo(info);
        if (Config.get().getBoolean(ConfigDefaults.WEB_SUBSCRIBE_PROXY_CHANNEL)) {
            Channel proxyChannel = ChannelManager.getProxyChannelByVersion(
                    version, server);
            if (proxyChannel != null) {
                subscribeServerToChannel(null, server, proxyChannel);
            }
        }
    }

    /**
     * Entitles the given server to the given Entitlement.
     * @param server Server to be entitled.
     * @param ent Level of Entitlement.
     * @return ValidatorResult of errors and warnings.
     */
    public static ValidatorResult entitleServer(Server server, Entitlement ent) {
        log.debug("Entitling: " + ent.getLabel());

        return entitleServer(server.getOrg(), server.getId(), ent);
    }

    /**
     * Entitles the given server to the given Entitlement.
     * @param orgIn Org who wants to entitle the server.
     * @param sid server id to be entitled.
     * @param ent Level of Entitlement.
     * @return ValidatorResult of errors and warnings.
     */
    public static ValidatorResult entitleServer(Org orgIn, Long sid,
            Entitlement ent) {
        Server server = ServerFactory.lookupByIdAndOrg(sid, orgIn);
        ValidatorResult result = new ValidatorResult();

        if (hasEntitlement(sid, ent)) {
            log.debug("server already entitled.");
            result.addError(new ValidatorError("system.entitle.alreadyentitled",
                    ent.getHumanReadableLabel()));
            return result;
        }
        if (EntitlementManager.VIRTUALIZATION.equals(ent)) {
            if (server.isVirtualGuest()) {
                result.addError(new ValidatorError("system.entitle.guestcantvirt"));
                return result;
            }

            if (!hasEntitlement(sid, EntitlementManager.VIRTUALIZATION)) {
                log.debug("setting up system for virt.");
                ValidatorResult virtSetupResults = setupSystemForVirtualization(orgIn, sid);
                result.append(virtSetupResults);
                if (virtSetupResults.getErrors().size() > 0) {
                    log.debug("error trying to setup virt ent: " +
                            virtSetupResults.getMessage());
                    return result;
                }
            }
        }

        Map<String, Object> in = new HashMap<String, Object>();
        in.put("sid", sid);
        in.put("entitlement", ent.getLabel());

        CallableMode m = ModeFactory.getCallableMode(
                "System_queries", "entitle_server");

        m.execute(in, new HashMap<String, Integer>());
        log.debug("entitle_server mode query executed.");

        server.asMinionServer().ifPresent(SystemManager::refreshPillarDataForMinion);

        log.debug("done.  returning null");
        return result;
    }

    /**
     * Refresh pillar data for a minion.
     * @param minion to refresh
     */
    private static void refreshPillarDataForMinion(MinionServer minion) {
        SaltStateGeneratorService.INSTANCE.generatePillar(minion);
        //SaltService.INSTANCE.refreshPillar(
        //        new MinionList(minion.getMinionId()));
        log.debug("Refreshed pillars for minion.");
    }

    // Need to do some extra logic here
    // 1) Subscribe system to rhel-i386-server-vt-5 channel
    // 2) Subscribe system to rhn-tools-rhel-i386-server-5
    // 3) Schedule package install of rhn-virtualization-host
    // Return a map with errors and warnings:
    //      warnings -> list of ValidationWarnings
    //      errors -> list of ValidationErrors
    private static ValidatorResult setupSystemForVirtualization(Org orgIn, Long sid) {

        Server server = ServerFactory.lookupById(sid);
        User user = UserFactory.findRandomOrgAdmin(orgIn);
        ValidatorResult result = new ValidatorResult();

        // If this is a Satellite
        if (!ConfigDefaults.get().isSpacewalk()) {
            // just install libvirt for RHEL6 base channel
            Channel base = server.getBaseChannel();

            if (base != null && base.isCloned()) {
                base = base.getOriginal();
            }

            if ((base != null) &&
                    (!base.isRhelChannel() || base.isReleaseXChannel(5))) {
                // Do not automatically subscribe to virt channels (bnc#768856)
                // subscribeToVirtChannel(server, user, result);
            }
        }

        if (server.hasEntitlement(EntitlementManager.MANAGEMENT)) {
            // Before we start looking to subscribe to a 'tools' channel for
            // rhn-virtualization-host, check if the server already has a package by this
            // name installed and leave it be if so.
            InstalledPackage rhnVirtHost = PackageFactory.lookupByNameAndServer(
                    ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME, server);
            if (rhnVirtHost != null) {
                // System already has the package, we can stop here.
                log.debug("System already has " +
                        ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME + " installed.");
                return result;
            }
            try {
                scheduleVirtualizationHostPackageInstall(server, user, result);
            }
            catch (TaskomaticApiException e) {
                result.addError(new ValidatorError("taskscheduler.down"));
            }
        }

        return result;
    }

    /**
     * Schedule installation of rhn-virtualization-host package.
     *
     * Implies that we locate a child channel with this package and automatically
     * subscribe the system to it if possible. If multiple child channels have the package
     * and the server is not already subscribed to one, we report the discrepancy and
     * instruct the user to deal with this manually.
     *
     * @param server Server to schedule install for.
     * @param user User performing the operation.
     * @param result Validation result we'll be returning for the UI to render.
     * @throws TaskomaticApiException if there was a Taskomatic error
     * (typically: Taskomatic is down)
     */
    private static void scheduleVirtualizationHostPackageInstall(Server server,
            User user, ValidatorResult result) throws TaskomaticApiException {
        // Now subscribe to a child channel with rhn-virtualization-host (RHN Tools in the
        // case of Satellite) and schedule it for installation, or warn if we cannot find
        // a child with the package:
        Channel toolsChannel = null;
        try {
            toolsChannel = ChannelManager.subscribeToChildChannelWithPackageName(
                    user, server, ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME);

            // If this is a Satellite and no RHN Tools channel is available
            // report the error
            if (!ConfigDefaults.get().isSpacewalk() && toolsChannel == null) {
                log.warn("no tools channel found");
                result.addError(new ValidatorError("system.entitle.notoolschannel"));
            }
            // If Spacewalk and no channel has the rhn-virtualization-host package,
            // warn but allow the operation to proceed.
            else if (toolsChannel == null) {
                result.addWarning(new ValidatorWarning("system.entitle.novirtpackage",
                        ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME));
            }
            else {
                List<Map<String, Object>> packageResults =
                        ChannelManager.listLatestPackagesEqual(
                        toolsChannel.getId(), ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME);
                if (packageResults.size() > 0) {
                    Map<String, Object> row = packageResults.get(0);
                    Long nameId = (Long) row.get("name_id");
                    Long evrId = (Long) row.get("evr_id");
                    Long archId = (Long) row.get("package_arch_id");
                    ActionManager.schedulePackageInstall(
                            user, server, nameId, evrId, archId);
                }
                else {
                    result.addError(new ValidatorError("system.entitle.novirtpackage",
                            ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME));
                }
            }
        }
        catch (MultipleChannelsWithPackageException e) {
            log.warn("Found multiple child channels with package: " +
                    ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME);
            result.addWarning(new ValidatorWarning(
                    "system.entitle.multiplechannelswithpackagepleaseinstall",
                    ChannelManager.RHN_VIRT_HOST_PACKAGE_NAME));
        }
    }

    /**
     * Subscribe the system to the Red Hat Virtualization channel if necessary.
     *
     * This method should only ever be called in Satellite.
     *
     * @param server Server to schedule install for.
     * @param user User performing the operation.
     * @param result Validation result we'll be returning for the UI to render.
     */
    private static void subscribeToVirtChannel(Server server, User user,
            ValidatorResult result) {
        Channel virtChannel = ChannelManager.subscribeToChildChannelByOSProduct(
                user, server, ChannelManager.VT_OS_PRODUCT);
        log.debug("virtChannel search by OS product found: " + virtChannel);
        // Otherwise, try just searching by package name: (libvirt in this case)
        if (virtChannel == null) {
            log.debug("Couldnt find a virt channel by OS/Product mappings, " +
                    "trying package");
            try {
                virtChannel = ChannelManager.subscribeToChildChannelWithPackageName(
                        user, server, ChannelManager.VIRT_CHANNEL_PACKAGE_NAME);

                // If we couldn't find a virt channel, warn the user but continue:
                if (virtChannel == null) {
                    log.warn("no virt channel");
                    result.addError(new ValidatorError(
                            "system.entitle.novirtchannel"));
                }
            }
            catch (MultipleChannelsWithPackageException e) {
                log.warn("Found multiple child channels with package: " +
                        ChannelManager.VIRT_CHANNEL_PACKAGE_NAME);
                result.addWarning(new ValidatorWarning(
                        "system.entitle.multiplechannelswithpackage",
                        ChannelManager.VIRT_CHANNEL_PACKAGE_NAME));
            }
        }
    }

    /**
     * Removes all the entitlements related to a server..
     * @param sid server id to be unentitled.
     */
    public static void removeAllServerEntitlements(Long sid) {
        Map<String, Object> in = new HashMap<String, Object>();
        in.put("sid", sid);
        CallableMode m = ModeFactory.getCallableMode(
                "System_queries", "unentitle_server");
        m.execute(in, new HashMap<String, Integer>());
    }


    /**
     * Removes a specific level of entitlement from the given Server.
     * @param sid server id to be unentitled.
     * @param ent Level of Entitlement.
     */
    public static void removeServerEntitlement(Long sid,
            Entitlement ent) {

        if (!hasEntitlement(sid, ent)) {
            if (log.isDebugEnabled()) {
                log.debug("server doesnt have entitlement: " + ent);
            }
            return;
        }

        Map<String, Object> in = new HashMap<String, Object>();
        in.put("sid", sid);
        in.put("entitlement", ent.getLabel());
        CallableMode m = ModeFactory.getCallableMode(
                "System_queries", "remove_server_entitlement");
        m.execute(in, new HashMap<String, Integer>());

        Server server = ServerFactory.lookupById(sid);
        server.asMinionServer().ifPresent(SystemManager::refreshPillarDataForMinion);
    }


    /**
     * Tests whether or not a given server can be entitled with a specific entitlement
     * @param server The server in question
     * @param ent The entitlement to test
     * @return Returns true or false depending on whether or not the server can be
     * entitled to the passed in entitlement.
     */
    public static boolean canEntitleServer(Server server, Entitlement ent) {
        return canEntitleServer(server.getId(), ent);
    }

    /**
     * Tests whether or not a given server can be entitled with a specific entitlement
     * @param serverId The Id of the server in question
     * @param ent The entitlement to test
     * @return Returns true or false depending on whether or not the server can be
     * entitled to the passed in entitlement.
     */
    public static boolean canEntitleServer(Long serverId, Entitlement ent) {
        if (log.isDebugEnabled()) {
            log.debug("canEntitleServer.serverId: " + serverId + " ent: " +
                    ent.getHumanReadableLabel());
        }
        Map<String, Object> in = new HashMap<String, Object>();
        in.put("sid", serverId);
        in.put("entitlement", ent.getLabel());

        Map<String, Integer> out = new HashMap<String, Integer>();
        out.put("retval", new Integer(Types.NUMERIC));

        CallableMode m = ModeFactory.getCallableMode("System_queries",
                "can_entitle_server");
        Map<String, Object> result = m.execute(in, out);
        boolean retval = BooleanUtils.
                toBoolean(((Long) result.get("retval")).intValue());
        log.debug("canEntitleServer.returning: " + retval);
        return retval;
    }

    /**
     * Returns a DataResult containing the systems subscribed to a particular channel.
     *      but returns a DataResult of SystemOverview objects instead of maps
     * @param channel The channel in question
     * @param user The user making the call
     * @return Returns a DataResult of maps containing the ids and names of systems
     * subscribed to a channel.
     */
    public static DataResult<Map<String, Object>> systemsSubscribedToChannelDto(
            Channel channel, User user) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("cid", channel.getId());
        params.put("org_id", user.getOrg().getId());
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_subscribed_to_channel", SystemOverview.class);
        return m.execute(params);
    }

    /**
     * Returns the number of systems subscribed to the given channel.
     *
     * @param channelId identifies the channel
     * @param user      user making the request
     * @return number of systems subscribed to the channel
     */
    public static int countSystemsSubscribedToChannel(Long channelId, User user) {
        Map<String, Long> params = new HashMap<String, Long>(2);
        params.put("user_id", user.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("cid", channelId);

        SelectMode m = ModeFactory.getMode("System_queries",
                "count_systems_subscribed_to_channel");
        DataResult<Map<String, Object>> dr = makeDataResult(params, params, null, m);

        Map<String, Object> result = dr.get(0);
        Long count = (Long) result.get("count");
        return count.intValue();
    }

    /**
     * Returns a DataResult containing the systems subscribed to a particular channel.
     * @param channel The channel in question
     * @param user The user making the call
     * @return Returns a DataResult of maps containing the ids and names of systems
     * subscribed to a channel.
     */
    public static DataResult<Map<String, Object>> systemsSubscribedToChannel(
            Channel channel, User user) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("cid", channel.getId());
        params.put("org_id", user.getOrg().getId());

        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_subscribed_to_channel", Map.class);
        return m.execute(params);
    }

    /**
     * Return the list of systems subscribed to the given channel in the current set.
     * Each entry in the result will be of type EssentialServerDto as per the query.
     *
     * @param cid Channel
     * @param user User requesting the list
     * @param setLabel Set label
     * @return List of systems
     */
    public static DataResult<EssentialServerDto> systemsSubscribedToChannelInSet(
            Long cid, User user, String setLabel) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("cid", cid);
        params.put("org_id", user.getOrg().getId());
        params.put("set_label", setLabel);

        SelectMode m = ModeFactory.getMode(
                "System_queries", "systems_subscribed_to_channel_in_set");
        return m.execute(params);
    }

    /**
     * Returns a DataResult containing maps representing the channels a particular system
     * is subscribed to.
     * @param server The server in question.
     * @return Returns a DataResult of maps representing the channels a particular system
     * is subscribed to.
     */
    public static DataResult<Map<String, Object>> channelsForServer(Server server) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", server.getId());
        SelectMode m = ModeFactory.getMode("Channel_queries", "system_channels", Map.class);
        return m.execute(params);
    }

    /**
     * Unlocks a server if the user has permissions on the server
     * @param user User who is attempting to unlock the server
     * @param server Server that is attempting to be unlocked
     */
    public static void unlockServer(User user, Server server) {
        if (!isAvailableToUser(user, server.getId())) {
            LocalizationService ls = LocalizationService.getInstance();
            LookupException e = new LookupException(
                    "Could not find server " + server.getId() +
                    " for user " + user.getId());
            e.setLocalizedTitle(ls.getMessage("lookup.jsp.title.system"));
            e.setLocalizedReason1(ls.getMessage("lookup.jsp.reason1.system"));
            e.setLocalizedReason2(ls.getMessage("lookup.jsp.reason2.system"));
            throw e;
        }
        HibernateFactory.getSession().delete(server.getLock());
        server.setLock(null);
    }

    /**
     * Locks a server if the user has permissions on the server
     * @param locker User who is attempting to lock the server
     * @param server Server that is attempting to be locked
     * @param reason String representing the reason the server was locked
     */
    public static void lockServer(User locker, Server server, String reason) {
        if (!isAvailableToUser(locker, server.getId())) {
            LocalizationService ls = LocalizationService.getInstance();
            LookupException e = new LookupException(
                    "Could not find server " + server.getId() +
                    " for user " + locker.getId());
            e.setLocalizedTitle(ls.getMessage("lookup.jsp.title.system"));
            e.setLocalizedReason1(ls.getMessage("lookup.jsp.reason1.system"));
            e.setLocalizedReason2(ls.getMessage("lookup.jsp.reason2.system"));
            throw e;
        }
        ServerLock sl = new ServerLock(locker,
                server,
                reason);

        server.setLock(sl);
    }

    /**
     * Checks if the user has permissions to see the Server
     * @param user User being checked
     * @param sid ID of the Server being checked
     * @return true if the user can see the server, false otherwise
     */
    public static boolean isAvailableToUser(User user, Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "is_available_to_user");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("uid", user.getId());
        params.put("sid", sid);
        return m.execute(params).size() >= 1;
    }

    /**
     * Checks if the System is a virtual host
     * @param oid id of the Org that the server is in
     * @param sid ID of the Server being checked
     * @return true if the system is a virtual host, false otherwise
     */
    public static boolean isVirtualHost(Long oid, Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "is_virtual_host_in_org");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("oid", oid);
        params.put("sid", sid);
        DataResult result = m.execute(params);
        return !result.isEmpty();
    }

    /**
     * Checks if the user has permissions to see the Server
     * @param user User being checked
     * @param sid ID of the Server being checked
     */
    public static void ensureAvailableToUser(User user, Long sid) {
        if (!isAvailableToUser(user, sid)) {
            LocalizationService ls = LocalizationService.getInstance();
            LookupException e = new LookupException("Could not find server " + sid +
                    " for user " + user.getId());
            e.setLocalizedTitle(ls.getMessage("lookup.jsp.title.system"));
            e.setLocalizedReason1(ls.getMessage("lookup.jsp.reason1.system"));
            e.setLocalizedReason2(ls.getMessage("lookup.jsp.reason2.system"));
            throw e;
        }
    }

    /**
     * Return systems in the current set without a base channel.
     * @param user User requesting the query.
     * @return List of systems.
     */
    public static DataResult<EssentialServerDto> systemsWithoutBaseChannelsInSet(
            User user) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_in_set_with_no_base_channel");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        return m.execute(params);
    }


    /**
     * Validates that the proposed number of virtual CPUs is valid for the
     * given virtual instance.
     *
     * @param guestId ID of a virtual instance.
     * @param proposedVcpuSetting Requested number of virtual CPUs for the guest.
     * @return ValidatorResult containing both error and warning messages.
     */
    public static ValidatorResult validateVcpuSetting(Long guestId,
            int proposedVcpuSetting) {
        ValidatorResult result = new ValidatorResult();

        VirtualInstanceFactory viFactory = VirtualInstanceFactory.getInstance();
        VirtualInstance guest = viFactory.lookupById(guestId);
        Server host = guest.getHostSystem();

        // Technically the limit is 32 for 32-bit architectures and 64 for 64-bit,
        // but the kernel is currently set to only accept 32 in either case. This may
        // need to change down the road.
        if (0 > proposedVcpuSetting || proposedVcpuSetting > 32) {
            result.addError(new ValidatorError(
                    "systems.details.virt.vcpu.limit.msg",
                    new Object [] {"32", guest.getName()}));
        }

        if (result.getErrors().isEmpty()) {
            // Warn the user if the proposed vCPUs exceeds the physical CPUs on the
            // host:
            CPU hostCpu = host.getCpu();
            if (hostCpu != null && hostCpu.getNrCPU() != null) {
                if (proposedVcpuSetting > hostCpu.getNrCPU().intValue()) {
                    result.addWarning(new ValidatorWarning(
                            "systems.details.virt.vcpu.exceeds.host.cpus",
                            new Object [] {host.getCpu().getNrCPU(), guest.getName()}));
                }
            }

            // Warn the user if the proposed vCPUs is an increase for this guest.
            // If the new value exceeds the setting the guest was started with, a
            // reboot will be required for the setting to take effect.
            VirtualInstanceState running = VirtualInstanceFactory.getInstance().
                    getRunningState();
            if (guest.getState() != null &&
                    guest.getState().getId().equals(running.getId())) {
                Integer currentGuestCpus = guest.getNumberOfCPUs();
                if (currentGuestCpus != null && proposedVcpuSetting >
                currentGuestCpus.intValue()) {
                    result.addWarning(new ValidatorWarning(
                            "systems.details.virt.vcpu.increase.warning",
                            new Object [] {new Integer(proposedVcpuSetting),
                                guest.getName()}));
                }
            }
        }

        return result;
    }

    /**
     * Validates the amount requested amount of memory can be allocated to each
     * of the guest systems in the list. Assumes all guests are on the same host.
     *
     * @param guestIds List of longs representing IDs of virtual instances.
     * @param proposedMemory Requested amount of memory for each guest. (in Mb)
     * @return ValidatorResult containing both error and warning messages.
     */
    public static ValidatorResult validateGuestMemorySetting(List<Long> guestIds,
            int proposedMemory) {
        ValidatorResult result = new ValidatorResult();
        VirtualInstanceFactory viFactory = VirtualInstanceFactory.getInstance();

        if (guestIds.isEmpty()) {
            return result;
        }

        // Grab the host from the first guest in the list:
        Long firstGuestId = guestIds.get(0);
        Server host = (viFactory.lookupById(firstGuestId)).
                getHostSystem();

        VirtualInstanceState running = VirtualInstanceFactory.getInstance().
                getRunningState();

        log.debug("Adding guest memory:");
        List<ValidatorWarning> warnings = new LinkedList<ValidatorWarning>();
        for (Iterator<VirtualInstance> it = host.getGuests().iterator(); it.hasNext();) {
            VirtualInstance guest = it.next();

            // if the guest we're examining isn't running, don't count it's memory
            // when determining if the host has enough free:
            if (guest.getState() != null &&
                    guest.getState().getId().equals(running.getId())) {

                if (guest.getTotalMemory() != null) {
                    log.debug("   " + guest.getName() + " = " +
                            (guest.getTotalMemory().longValue() / 1024) + "MB");

                    if (guestIds.contains(guest.getId())) {
                        // Warn the user that a change to max memory will require a reboot
                        // for the settings to take effect:
                        warnings.add(new ValidatorWarning(
                                "systems.details.virt.memory.warning",
                                new Object [] {guest.getName()}));
                    }
                }
                else {
                    // Not much we can do for calculations if we don't have reliable data,
                    // continue on to other guests:
                    log.warn("No total memory set for guest: " + guest.getName());
                }
            }
        }

        // Warn the user to verify the system has enough free memory:
        // NOTE: Once upon a time we tried to do this automagically but the
        // code was removed due to uncertainty in terms of rebooting guests
        // if increasing past the allocation they were booted with, missing
        // hardware refreshes for the host, etc.
        warnings.add(new ValidatorWarning("systems.details.virt.memory.check.host"));

        if (!warnings.isEmpty()) {
            for (Iterator<ValidatorWarning> itr = warnings.iterator(); itr.hasNext();) {
                result.addWarning(itr.next());
            }
        }

        return result;
    }

    /**
     * Return the system names and IDs that are selected in the SSM for the given user,
     * which also have been subscribed to the given channel.
     *
     * @param user User.
     * @param channelId Channel ID.
     * @return List of maps containing the system name and ID.
     */
    public static List<Map<String, Object>> getSsmSystemsSubscribedToChannel(User user,
            Long channelId) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_in_set_with_channel");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("channel_id", channelId);
        return m.execute(params);
    }

    /**
     * lists  systems with the given installed NVR
     * @param user the user doing the search
     * @param name the name of the package
     * @param version package version
     * @param release package release
     * @return  list of systemOverview objects
     */
    public static List<SystemOverview> listSystemsWithPackage(User user,
            String name, String version, String release) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_with_package_nvr");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("version", version);
        params.put("release", release);
        params.put("name", name);
        DataResult<SystemOverview> toReturn = m.execute(params);
        toReturn.elaborate();
        return toReturn;
    }

    /**
     * lists  systems with the given installed package id
     * @param user the user doing the search
     * @param id the id of the package
     * @return  list of systemOverview objects
     */
    public static DataResult<SystemOverview> listSystemsWithPackage(User user, Long id) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_with_package");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("pid", id);
        return (DataResult<SystemOverview>) m.execute(params);
    }

    /**
     * lists systems that can upgrade to the package id
     * @param user the user doing the search
     * @param id the id of the package
     * @return  list of systemOverview objects
     */
    public static DataResult<SystemOverview> listPotentialSystemsForPackage(User user,
            Long id) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "potential_systems_for_package");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("pid", id);
        return (DataResult<SystemOverview>) m.execute(params);
    }

    /**
     * lists systems with the given needed/upgrade package id
     * @param user the user doing the search
     * @param id the id of the package
     * @return  list of systemOverview objects
     */
    public static List<SystemOverview> listSystemsWithNeededPackage(User user, Long id) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_with_needed_package");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("pid", id);
        //toReturn.elaborate();
        return (DataResult<SystemOverview>) m.execute(params);
    }

    /**
     * List all virtual hosts for a user
     * @param user the user in question
     * @return list of SystemOverview objects
     */
    public static List<SystemOverview> listVirtualHosts(User user) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "virtual_hosts_for_user");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        DataResult<SystemOverview> toReturn = m.execute(params);
        toReturn.elaborate();
        return toReturn;
    }

    /**
     * Returns the number of systems subscribed to the channel that are
     * <strong>not</strong> in the given org.
     *
     * @param orgId identifies the filter org
     * @param cid identifies the channel
     * @return count of systems
     */
    public static int countSubscribedToChannelWithoutOrg(Long orgId, Long cid) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "count_systems_subscribed_to_channel_not_in_org");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", orgId);
        params.put("cid", cid);

        DataResult<Map<String, Object>> dr = m.execute(params);
        Map<String, Object> result = dr.get(0);
        Long count = (Long) result.get("count");

        return count.intValue();
    }

    /**
     * List of servers subscribed to shared channels via org trust.
     * @param orgA The first org in the trust.
     * @param orgB The second org in the trust.
     * @return (system.id, system.org_id, system.name)
     */
    public static DataResult<Map<String, Object>>
            subscribedInOrgTrust(long orgA, long orgB) {
        SelectMode m =
                ModeFactory.getMode("System_queries",
                        "systems_subscribed_by_orgtrust");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orgA", orgA);
        params.put("orgB", orgB);
        return m.execute(params);
    }

    /**
     * List of distinct servers subscribed to shared channels via org trust.
     * @param orgA The first org in the trust.
     * @param orgB The second org in the trust.
     * @return (system.id)
     */
    public static DataResult<Map<String, Object>> sidsInOrgTrust(long orgA, long orgB) {
        SelectMode m =
                ModeFactory.getMode("System_queries",
                        "sids_subscribed_by_orgtrust");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orgA", orgA);
        params.put("orgB", orgB);
        return m.execute(params);
    }

    /**
     * gets the number of systems subscribed to a channel
     * @param user the user checking
     * @param cid the channel id
     * @return list of systems
     */
    public static Long subscribedToChannelSize(User user, Long cid) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_subscribed_to_channel_size");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("org_id", user.getOrg().getId());
        params.put("cid", cid);
        DataResult<Map<String, Object>> toReturn = m.execute(params);
        return (Long) toReturn.get(0).get("count");

    }

    /**
     * List all virtual hosts for a user
     * @param user the user in question
     * @return list of SystemOverview objects
     */
    public static DataResult<CustomDataKeyOverview> listDataKeys(User user) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "custom_vals", CustomDataKeyOverview.class);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("uid", user.getId());
        params.put("org_id", user.getOrg().getId());
        return (DataResult<CustomDataKeyOverview>) m.execute(params);
    }
    /**
     * Looks up a hardware device by the hardware device id
     * @param hwId the hardware device id
     * @return the HardwareDeviceDto
     */
    public static HardwareDeviceDto getHardwareDeviceById(Long hwId) {
        HardwareDeviceDto hwDto = null;
        SelectMode m = ModeFactory.getMode("System_queries", "hardware_device_by_id");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("hw_id", hwId);
        DataResult<HardwareDeviceDto> dr = m.execute(params);
        if (dr != null && !dr.isEmpty()) {
            hwDto = dr.get(0);
        }
        return hwDto;
    }

    /**
     * Returns a mapping of servers in the SSM to the user-selected packages to remove
     * that actually exist on those servers.
     *
     * @param user            identifies the user making the request
     * @param packageSetLabel identifies the RhnSet used to store the packages selected
     *                        by the user (this is needed for the query). This must be
     *                        established by the caller prior to calling this method
     * @param shortened       whether or not to include the full elaborator, or a shortened
     *                        one that is much much faster, but doesn't provide a displayed
     *                        string for the package (only the id combo)
     * @return description of server information as well as a list of relevant packages
     */
    public static DataResult<Map<String, Object>> ssmSystemPackagesToRemove(User user,
            String packageSetLabel,
            boolean shortened) {
        SelectMode m;
        if (shortened) {
            m = ModeFactory.getMode("System_queries",
                    "system_set_remove_or_verify_packages_conf_short");
        }
        else {
            m = ModeFactory.getMode("System_queries",
                    "system_set_remove_or_verify_packages_conf");
        }

        Map<String, Object> params = new HashMap<String, Object>(3);
        params.put("user_id", user.getId());
        params.put("set_label", RhnSetDecl.SYSTEMS.getLabel());
        params.put("package_set_label", packageSetLabel);

        return (DataResult<Map<String, Object>>) makeDataResult(params, params, null, m);
    }

    /**
     * Returns a mapping of servers in the SSM to user-selected packages to upgrade
     * that actually exist on those servers
     *
     * @param user            identifies the user making the request
     * @param packageSetLabel identifies the RhnSet used to store the packages selected
     *                        by the user (this is needed for the query). This must be
     *                        established by the caller prior to calling this method
     * @return description of server information as well as a list of all relevant packages
     */
    public static DataResult ssmSystemPackagesToUpgrade(User user,
            String packageSetLabel) {

        SelectMode m =
                ModeFactory.getMode("System_queries", "ssm_package_upgrades_conf");

        Map<String, Object> params = new HashMap<String, Object>(3);
        params.put("user_id", user.getId());
        params.put("set_label", RhnSetDecl.SYSTEMS.getLabel());
        params.put("package_set_label", packageSetLabel);

        return makeDataResult(params, params, null, m);
    }

    /**
     * Deletes the indicates note, assuming the user has the proper permissions to the
     * server.
     *
     * @param user     user making the request
     * @param serverId identifies server the note resides on
     * @param noteId   identifies the note being deleted
     */
    public static void deleteNote(User user, Long serverId, Long noteId) {
        Server server = lookupByIdAndUser(serverId, user);

        Session session = HibernateFactory.getSession();
        Note doomed = (Note) session.get(Note.class, noteId);

        boolean deletedOnServer = server.getNotes().remove(doomed);
        if (deletedOnServer) {
            session.delete(doomed);
        }
    }

    /**
     * Deletes all notes on the given server, assuming the user has the proper permissions
     * to the server.
     *
     * @param user     user making the request
     * @param serverId identifies the server on which to delete its notes
     */
    public static void deleteNotes(User user, Long serverId) {
        Server server = lookupByIdAndUser(serverId, user);

        Session session = HibernateFactory.getSession();
        for (Object doomed : server.getNotes()) {
            session.delete(doomed);
        }

        server.getNotes().clear();
    }

    /**
     * Is the package with nameId, archId, and evrId available in the
     *  provided server's subscribed channels
     * @param server the server
     * @param nameId the name id
     * @param archId the arch id
     * @param evrId the evr id
     * @return true if available, false otherwise
     */
    public static boolean hasPackageAvailable(Server server, Long nameId,
            Long archId, Long evrId) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("server_id", server.getId());
        params.put("eid", evrId);
        params.put("nid", nameId);

        String mode = "has_package_available";
        if (archId == null) {
            mode = "has_package_available_no_arch";
        }
        else {
            params.put("aid", archId);
        }
        SelectMode m =
                ModeFactory.getMode("System_queries", mode);
        DataResult toReturn = m.execute(params);
        return toReturn.size() > 0;
    }

    /**
     * Gets the list of proxies that the given system connects
     * through in order to reach the server.
     * @param sid The id of the server in question
     * @return Returns a list of ServerPath objects.
     */
    public static DataResult<ServerPath> getConnectionPath(Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "proxy_path_for_server");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        return m.execute(params);
    }

    /**
     * List all of the installed packages with the given name
     * @param packageName the package name
     * @param server the server
     * @return list of maps with name_id, evr_id and arch_id
     */
    public static List<Map<String, Long>> listInstalledPackage(
            String packageName, Server server) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "list_installed_packages_for_name");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", server.getId());
        params.put("name", packageName);
        return m.execute(params);
    }

    /**
     * returns a List proxies available in the given org
     * @param org needed for org information
     * @return list of proxies for org
     */
    public static DataResult<OrgProxyServer> listProxies(Org org) {
        DataResult<OrgProxyServer> retval = null;
        SelectMode mode = ModeFactory.getMode("System_queries",
                "org_proxy_servers");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", org.getId());
        retval = mode.execute(params);
        return retval;
    }

    /**
     * list systems that can be subscribed to a particular child channel
     * @param user the user
     * @param chan the child channle
     * @return list of SystemOverview objects
     */
    public static List<SystemOverview> listTargetSystemForChannel(User user, Channel chan) {
        DataResult<SystemOverview> retval = null;
        SelectMode mode = ModeFactory.getMode("System_queries",
                "target_systems_for_channel");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("cid", chan.getId());
        params.put("org_id", user.getOrg().getId());
        retval = mode.execute(params);
        retval.setElaborationParams(new HashMap<String, Object>());
        return retval;
    }

    /**
     * Get a list of SystemOverview objects for the systems in an rhnset
     * @param user the user doing the lookup
     * @param setLabel the label of the set
     * @return List of SystemOverview objects
     */
    public static List<SystemOverview> inSet(User user, String setLabel) {
        return inSet(user, setLabel, false);
    }


    /**
     * Get a list of SystemOverview objects for the systems in an rhnset with option
     * to elaborate the result
     * @param user the user doing the lookup
     * @param setLabel the label of the set
     * @param elaborate elaborate results
     * @return List of SystemOverview objects
     */
    public static List<SystemOverview> inSet(User user, String setLabel,
            boolean elaborate) {
        DataResult<SystemOverview> retval = null;
        SelectMode mode = ModeFactory.getMode("System_queries",
                "in_set");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        retval = mode.execute(params);
        retval.setElaborationParams(new HashMap<String, Object>());
        if (elaborate) {
            retval.elaborate();
        }
        return retval;
    }

    /**
     * Find a system by it's name (must be an exact string match)
     * @param user  the user doing the search
     * @param name the name of the system
     * @return the SystemOverview objects with the matching name
     */
    public static List<SystemOverview> listSystemsByName(User user,
            String name) {
        SelectMode mode = ModeFactory.getMode("System_queries", "find_by_name");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("name", name);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        DataResult<SystemOverview> result =
                makeDataResult(params, elabParams, null, mode, SystemOverview.class);
        result.elaborate();
        return result;
    }


    private static DataResult<SystemOverview> listDuplicates(User user,
            String query, String key) {
        SelectMode mode = ModeFactory.getMode("System_queries", query);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("uid", user.getId());
        params.put("key", key);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, null, mode, SystemOverview.class);
    }

    private static List<DuplicateSystemGrouping> listDuplicates(User user, String query,
            List<String> ignored, Long inactiveHours) {

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, (0 - inactiveHours.intValue()));

        SelectMode ipMode = ModeFactory.getMode("System_queries",
                query);

        Date d = new Date(cal.getTimeInMillis());

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("uid", user.getId());
        params.put("inactive_date", d);
        DataResult<NetworkDto> nets;
        if (ignored.isEmpty()) {
            nets = ipMode.execute(params);
        }
        else {
            nets = ipMode.execute(params, ignored);
        }


        List<DuplicateSystemGrouping> nodes = new ArrayList<DuplicateSystemGrouping>();
        for (NetworkDto net : nets) {
            boolean found = false;
            for (DuplicateSystemGrouping node : nodes) {
                if (node.addIfMatch(net)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                nodes.add(new DuplicateSystemGrouping(net));
            }
        }
        return nodes;
    }

    /**
     * List duplicate systems by ip address
     * @param user the user doing the search
     * @param inactiveHours the number of hours a system hasn't checked in
     *          to consider it inactive
     * @return List of DuplicateSystemGrouping objects
     */
    public static List<DuplicateSystemGrouping> listDuplicatesByIP(User user,
            long inactiveHours) {
        List<String> ignoreIps = new ArrayList<String>();
        ignoreIps.add("127.0.0.1");
        ignoreIps.add("127.0.0.01");
        ignoreIps.add("127.0.0.2");
        ignoreIps.add("0");
        return listDuplicates(user, "duplicate_system_ids_ip", ignoreIps, inactiveHours);
    }

    /**
     * List duplicate systems by ip address
     * @param user the user doing the search
     * @param ip  ip address of the system
     * @return List of DuplicateSystemGrouping objects
     */
    public static  List<SystemOverview> listDuplicatesByIP(User user, String ip) {
        return listDuplicates(user, "duplicate_system_ids_ip_key", ip);
    }

    /**
     * List duplicate systems by ipv6 address
     * @param user the user doing the search
     * @param inactiveHours the number of hours a system hasn't checked in
     *          to consider it inactive
     * @return List of DuplicateSystemGrouping objects
     */
    public static List<DuplicateSystemGrouping> listDuplicatesByIPv6(User user,
            long inactiveHours) {
        List<String> ignoreIps = new ArrayList<String>();
        ignoreIps.add("::1");
        return listDuplicates(user, "duplicate_system_ids_ipv6", ignoreIps, inactiveHours);
    }

    /**
     * List duplicate systems by ipv6 address
     * @param user the user doing the search
     * @param ip  ip address of the system
     * @return List of DuplicateSystemGrouping objects
     */
    public static  List<SystemOverview> listDuplicatesByIPv6(User user, String ip) {
        return listDuplicates(user, "duplicate_system_ids_ipv6_key", ip);
    }

    /**
     * List duplicate systems by mac address
     * @param user the user doing the search
     * @param inactiveHours the number of hours a system hasn't checked in
     *          to consider it inactive
     * @return List of DuplicateSystemGrouping objects
     */
    public static List<DuplicateSystemGrouping> listDuplicatesByMac(User user,
            Long inactiveHours) {
        List<String> ignoreMacs = new ArrayList<String>();
        ignoreMacs.add("00:00:00:00:00:00");
        ignoreMacs.add("fe:ff:ff:ff:ff:ff");
        return listDuplicates(user, "duplicate_system_ids_mac", ignoreMacs, inactiveHours);
    }

    /**
     * List duplicate systems by mac address
     * @param user the user doing the search
     * @param mac the mac address of the system
     * @return List of DuplicateSystemGrouping objects
     */
    public static List<SystemOverview> listDuplicatesByMac(User user, String mac) {
        return listDuplicates(user, "duplicate_system_ids_mac_key", mac);
    }

    /**
     * List duplicate systems by hostname
     * @param user the user doing the search
     * @param inactiveHours the number of hours a system hasn't checked in
     *          to consider it inactive
     * @return List of DuplicateSystemBucket objects
     */
    public static List<DuplicateSystemGrouping> listDuplicatesByHostname(User user,
            Long inactiveHours) {
        List<DuplicateSystemGrouping> duplicateSystems = listDuplicates(user,
                "duplicate_system_ids_hostname",
                        new ArrayList<String>(), inactiveHours);
        ListIterator<DuplicateSystemGrouping> litr = duplicateSystems.listIterator();
        while (litr.hasNext()) {
            DuplicateSystemGrouping element = litr.next();
            element.setKey(IDN.toUnicode(element.getKey()));
        }
        return duplicateSystems;
    }

    /**
     * List duplicate systems by hostName
     * @param user the user doing the search
     * @param hostName host name of the system
     * @return List of DuplicateSystemGrouping objects
     */
    public static List<SystemOverview> listDuplicatesByHostname(User user,
            String hostName) {
        return listDuplicates(user, "duplicate_system_ids_hostname_key",
                hostName);
    }

    /**
     * Return a note by ID and Server ID
     * @param user User to use to do the lookups
     * @param nid note ID
     * @param sid server ID
     * @return Note object
     */
    public static Note lookupNoteByIdAndSystem(User user, Long nid, Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "note_by_id_and_server");
        Note n = new Note();
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("nid", nid);
        params.put("sid", sid);
        DataResult<Map<String, Object>> dr = m.execute(params);
        for (Map<String, Object> map : dr) {
            n.setCreator(UserManager.lookupUser(user, (Long)map.get("creator")));
            n.setId((Long)map.get("id"));
            n.setServer(lookupByIdAndUser((Long)map.get("server_id"), user));
            n.setSubject((String)map.get("subject"));
            n.setNote((String)map.get("note"));
            n.setModified(Date.valueOf((String)map.get("modified")));
        }
        return n;
    }

    /**
     * Lookup all the custom info keys not assigned to this server
     * @param orgId The org ID that the server belongs to
     * @param sid The ID of the server
     * @return DataResult of keys
     */
    public static DataResult<Map<String, Object>> lookupKeysSansValueForServer(Long orgId,
            Long sid) {
        SelectMode m = ModeFactory.getMode("CustomInfo_queries",
                "custom_info_keys_sans_value_for_system");
        Map<String, Object> inParams = new HashMap<String, Object>();

        inParams.put("org_id", orgId);
        inParams.put("sid", sid);

        return m.execute(inParams);
    }

    /**
     * @param sid server id
     * @param oid organization id
     * @param pc pageContext
     * @return Returns history events for a system
     */
    public static DataResult<SystemEventDto> systemEventHistory(Long sid, Long oid,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "system_events_history");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("oid", oid);

        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemEventDto.class);
    }

    /**
     * @param sid server id
     * @param pc pageContext
     * @return Returns system snapshot list
     */
    public static DataResult<Map<String, Object>> systemSnapshots(Long sid,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("General_queries", "system_snapshots");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * @param sid server id
     * @param ssid snapshot id
     * @param pc pageContext
     * @return Returns system vs. snapshot packages comparision list
     */
    public static DataResult<Map<String, Object>> systemSnapshotPackages(Long sid,
            Long ssid, PageControl pc) {
        SelectMode m = ModeFactory.getMode("Package_queries",
                                           "compare_packages_to_snapshot");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("ss_id", ssid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * @param sid server id
     * @param ssid snapshot id
     * @param pc pageContext
     * @return Returns system vs. snapshot groups comparision list
     */
    public static DataResult<Map<String, Object>> systemSnapshotGroups(Long sid, Long ssid,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("SystemGroup_queries",
                                           "snapshot_group_diff");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("ss_id", ssid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * @param sid server id
     * @param ssid snapshot id
     * @param pc pageContext
     * @return Returns system vs. snapshot channels comparision list
     */
    public static DataResult<Map<String, Object>> systemSnapshotChannels(Long sid,
            Long ssid, PageControl pc) {
        SelectMode m = ModeFactory.getMode("Channel_queries",
                                           "snapshot_channel_diff");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("ss_id", ssid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * @param sid server id
     * @return Count of pending actions on system
     */
    public static Long countPendingActions(Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "system_events_history_count_pending");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        DataResult<Map<String, Object>> toReturn = m.execute(params);
        return (Long) toReturn.get(0).get("count");
    }

    /**
     * @param sid server id
     * @param pc pageContext
     * @return Returns pending actions for a system
     */
    public static DataResult<SystemPendingEventDto> systemPendingEvents(Long sid,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "system_events_pending");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SystemPendingEventDto.class);
    }

    /**
     * @param sid server id
     * @param pc pageControl
     * @return Returns snapshot tags for a system
     */
    public static DataResult<SnapshotTagDto> snapshotTagsForSystem(Long sid,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "tags_for_system");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SnapshotTagDto.class);
    }

    /**
     * @param sid server id
     * @param ssId snapshot ID
     * @param pc pageControl
     * @return Returns snapshot tags for a system
     */
    public static DataResult<SnapshotTagDto> snapshotTagsForSystemAndSnapshot(Long sid,
            Long ssId, PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "tags_for_system_and_snapshot");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("ss_id", ssId);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SnapshotTagDto.class);
    }

    /**
     * @param user user
     * @param pc PageControl
     * @param setLabel Set Label
     * @param sid Server ID
     * @return SnapshotTags in RHNSet
     */
    public static DataResult<SnapshotTagDto> snapshotTagsInSet(User user, PageControl pc,
            String setLabel, Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "snapshot_tags_in_set");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m, SnapshotTagDto.class);
    }

    /**
     * @param orgId organization ID
     * @param sid server id
     * @param ssId snapshot ID
     * @param pc pageControl
     * @return Returns unservable packages for a system
     */
    public static DataResult<Map<String, Object>> systemSnapshotUnservablePackages(
            Long orgId, Long sid,
            Long ssId, PageControl pc) {
        SelectMode m = ModeFactory.getMode("Package_queries",
                "snapshot_unservable_package_list");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("org_id", orgId);
        params.put("sid", sid);
        params.put("ss_id", ssId);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * @param uid user id
     * @param tid tag id
     * @return ssm systems with tag
     */
    public static DataResult systemsInSetWithTag(Long uid, Long tid) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "systems_in_set_with_tag");
        Map params = new HashMap();
        params.put("user_id", uid);
        params.put("tag_id",  tid);
        return m.execute(params);
    }

    /**
     * For a {@link ServerArch}, find the compatible {@link ChannelArch}.
     * @param serverArch server arch
     * @return channel arch
     */
    public static ChannelArch findCompatibleChannelArch(ServerArch serverArch) {
        SelectMode m = ModeFactory.getMode("System_queries",
                "find_compatible_channel_arch");
        Map params = new HashMap();
        params.put("server_arch_id", serverArch.getId());
        Long channelArchId = (Long) ((HashMap) makeDataResult(params, null, null, m).
                get(0)).get("channel_arch_id");
        return ChannelFactory.findArchById(channelArchId);
    }

    /**
     * Returns ids and names for systems in a given set with at least one of the
     * specified entitlements.
     * @param user the requesting user
     * @param setLabel the set label
     * @param entitlements the entitlement labels
     * @return a list of SystemOverview objects
     */
    @SuppressWarnings("unchecked")
    public static List<SystemOverview> entitledInSet(User user, String setLabel,
        List<String> entitlements) {
        SelectMode mode = ModeFactory.getMode("System_queries", "entitled_systems_in_set");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        DataResult<SystemOverview> result = mode.execute(params, entitlements);
        result.setElaborationParams(new HashMap<String, Object>());
        return result;
    }

    /**
     * Sets the custom info values for the systems in the set
     * @param user the requesting user
     * @param setLabel the set label
     * @param keyLabel the label of the custom value key
     * @param value the value to set for the custom value
     */
    public static void bulkSetCustomValue(User user, String setLabel, String keyLabel,
            String value) {
        CallableMode mode = ModeFactory.getCallableMode("System_queries",
                "bulk_set_custom_values");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        params.put("key_label", keyLabel);
        params.put("value", value);
        mode.execute(params, new HashMap<String, Integer>());
    }

    /**
     * Removes the custom info values from the systems in the set
     * @param user the requesting user
     * @param setLabel the set label
     * @param keyId the id of the custom value key
     * @return number of rows deleted
     */
    public static int bulkRemoveCustomValue(User user, String setLabel, Long keyId) {
        WriteMode mode = ModeFactory.getWriteMode("System_queries",
                "bulk_remove_custom_values");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("set_label", setLabel);
        params.put("key_id", keyId);
        return mode.executeUpdate(params);
    }

    /**
     * Get a list of all tags that are applicable to entitled systems in the set
     * @param user The user to check the system set for
     * @return Maps of id, name, tagged_systems, and date_tag_created
     */
    public static DataResult<Map<String, Object>> listTagsForSystemsInSet(User user) {
        SelectMode mode = ModeFactory.getMode("General_queries",
                "tags_for_entitled_in_set");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        return mode.execute(params);
    }

    /**
     * Set the vaules for the user system prefrence for all systems in the system set
     * @param user The user
     * @param preference The name of the preference to set
     * @param value The value to set
     * @param defaultIn The default value for the preference
     */
    public static void setUserSystemPreferenceBulk(User user, String preference,
            Boolean value, Boolean defaultIn) {
        CallableMode mode = ModeFactory.getCallableMode("System_queries",
                "reset_user_system_preference_bulk");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("pref", preference);
        mode.execute(params, new HashMap<String, Integer>());
        // preference values have a default, only insert if not default
        if (value != defaultIn) {
            mode = ModeFactory.getCallableMode("System_queries",
                    "set_user_system_preference_bulk");
            params = new HashMap<String, Object>();
            params.put("user_id", user.getId());
            params.put("pref", preference);
            params.put("value", value ? 1 : 0);
            mode.execute(params, new HashMap<String, Integer>());
        }
    }

    private static List<Long> errataIdsReleventToSystemSet(User user) {
        SelectMode mode = ModeFactory.getMode("System_queries",
                "unscheduled_relevant_to_system_set");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        List<Map<String, Object>> results = mode.execute(params);
        List<Long> ret = new ArrayList<Long>();
        for (Map<String, Object> result : results) {
            ret.add((Long) result.get("id"));
        }
        return ret;
    }

    /**
     * Set auto_update for all systems in the system set
     * @param user The user
     * @param value True if the servers should audo update
     * @throws TaskomaticApiException if there was a Taskomatic error
     * (typically: Taskomatic is down)
     */
    public static void setAutoUpdateBulk(User user, Boolean value)
        throws TaskomaticApiException {
        if (value) {
            // schedule all existing applicable errata
            List<SystemOverview> systems = inSet(user, "system_list");
            List<Long> sids = new ArrayList<Long>();
            for (SystemOverview system : systems) {
                sids.add(system.getId());
            }
            List<Long> eids = errataIdsReleventToSystemSet(user);
            java.util.Date earliest = new java.util.Date();
            ErrataManager.applyErrata(user, eids, earliest, sids);
        }
        CallableMode mode = ModeFactory.getCallableMode("System_queries",
                "set_auto_update_bulk");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("user_id", user.getId());
        params.put("value", value ? "Y" : "N");
        mode.execute(params, new HashMap<String, Integer>());
    }

    /**
     * Returns list bare metal systems visible to user.
     * @param user Currently logged in user.
     * @param pc PageControl
     * @return list of SystemOverviews
     */
    public static DataResult<SystemOverview> bootstrapList(User user,
            PageControl pc) {
        SelectMode m = ModeFactory.getMode("System_queries", "bootstrap");
        Map<String, Long> params = new HashMap<String, Long>();
        params.put("org_id", user.getOrg().getId());
        params.put("user_id", user.getId());
        Map<String, Long> elabParams = new HashMap<String, Long>();
        return makeDataResult(params, elabParams, pc, m);
    }

    /**
     * Returns list of client systems that connect through a proxy
     * @param sid System Id of the proxy to check.
     * @return list of SystemOverviews.
     */
    public static DataResult<SystemOverview> listClientsThroughProxy(Long sid) {
        SelectMode m = ModeFactory.getMode("System_queries", "clients_through_proxy");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        Map<String, Object> elabParams = new HashMap<String, Object>();
        return makeDataResult(params, elabParams, null, m, SystemOverview.class);
    }

    /**
     * Associate a particular system with a given capability. This is done by the python
     * backend code for traditional RHN clients. For other type of clients (e.g. Salt
     * minions), it can be done using this method.
     *
     * @param sid the server id
     * @param capability the capability to add as a string
     * @param version version number
     */
    public static void giveCapability(Long sid, String capability, Long version) {
        WriteMode m = ModeFactory.getWriteMode("System_queries",
                "add_to_client_capabilities");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("capability", capability);
        params.put("version", version);
        m.executeUpdate(params);
    }

    /**
     * Insert a new record into suseMinionInfo to hold the minion id.
     * @param sid server id
     * @param minionId the Salt minion id
     */
    public static void addMinionInfoToServer(Long sid, String minionId) {
        WriteMode m = ModeFactory.getWriteMode("System_queries",
                "add_minion_info");
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("sid", sid);
        params.put("minion_id", minionId);
        m.executeUpdate(params);
    }

}
