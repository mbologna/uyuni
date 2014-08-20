/**
 * Copyright (c) 2014 SUSE
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * SUSE trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate SUSE trademarks that are incorporated
 * in this software or its documentation.
 */

package com.redhat.rhn.frontend.xmlrpc.sync.content;

import com.redhat.rhn.domain.product.SUSEProductFactory;
import com.redhat.rhn.frontend.xmlrpc.BaseHandler;
import com.redhat.rhn.manager.content.ContentSyncException;
import com.redhat.rhn.manager.content.ContentSyncManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * @xmlrpc.namespace sync.content
 * @xmlrpc.doc Provides the namespace for the Content synchronization methods.
 */
public class ContentSyncHandler extends BaseHandler {

    /**
     * List all products that are accessible to the organization.
     *
     * @param sessionKey Session token.
     * @return List of products with their extensions (add-ons).
     * @throws ContentSyncException if content cannot be synchronized
     *
     * @xmlrpc.doc List all products that are accessible to the organization.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #array()
     *                       $SCCProductSerializer
     *                    #array_end()
     */
    public Object[] listProducts(String sessionKey) throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        ContentSyncManager csm = new ContentSyncManager();
        return csm.listProducts(csm.getAvailableChannels(csm.readChannels())).toArray();
    }

    /**
     * List all channels that are accessible to the organization.
     *
     * @param sessionKey Session Key
     * @return List of channels.
     * @throws com.redhat.rhn.manager.content.ContentSyncException
     *
     * @xmlrpc.doc List all channels that are accessible to the organization.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #array()
     *                       $MgrSyncChannelSerializer
     *                    #array_end()
     */
    public Object[] listChannels(String sessionKey) throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        ContentSyncManager csm = new ContentSyncManager();
        return csm.listChannels(csm.getRepositories()).toArray();
    }

    /**
     * Synchronize channels between the Customer Center and the SUSE Manager database.
     * This method is one step of the whole refresh cycle.
     *
     * @param sessionKey User session token.
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Synchronize channels between the Customer Center
     *             and the SUSE Manager database.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer synchronizeChannels(String sessionKey) throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        new ContentSyncManager().updateChannels();
        return BaseHandler.VALID;
    }

    /**
     * Synchronize channel families between the Customer Center
     * and the SUSE Manager database.
     * This method is one step of the whole refresh cycle.
     *
     * @param sessionKey User session token.
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Synchronize channel families between the Customer Center
     *             and the SUSE Manager database.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer synchronizeChannelFamilies(String sessionKey)
            throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        ContentSyncManager csm = new ContentSyncManager();
        csm.updateChannelFamilies(csm.readChannelFamilies());
        return BaseHandler.VALID;
    }

    /**
     * Synchronize SUSE products between the Customer Center and the SUSE Manager database.
     * This method is one step of the whole refresh cycle.
     *
     * @param sessionKey User session token.
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Synchronize SUSE products between the Customer Center
     *             and the SUSE Manager database.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer synchronizeProducts(String sessionKey) throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        ContentSyncManager csm = new ContentSyncManager();
        csm.updateSUSEProducts(csm.getProducts());
        return BaseHandler.VALID;
    }

    /**
     * Synchronize SUSE product channels between the Customer Center
     * and the SUSE Manager database.
     * This method is one step of the whole refresh cycle.
     *
     * @param sessionKey User session token.
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Synchronize SUSE product channels between the Customer Center
     *             and the SUSE Manager database.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer synchronizeProductChannels(String sessionKey)
            throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        ContentSyncManager csm = new ContentSyncManager();
        csm.updateSUSEProductChannels(csm.getAvailableChannels(csm.readChannels()));
        return BaseHandler.VALID;
    }

    /**
     * Synchronize upgrade paths between the Customer Center
     * and the SUSE Manager database.
     * This method is one step of the whole refresh cycle.
     *
     * @param sessionKey User session token.
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Synchronize upgrade paths between the Customer Center
     *             and the SUSE Manager database.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer synchronizeUpgradePaths(String sessionKey) throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        new ContentSyncManager().updateUpgradePaths();
        return BaseHandler.VALID;
    }

    /**
     * Synchronize subscriptions between the Customer Center
     * and the SUSE Manager database.
     * This method is one step of the whole refresh cycle.
     *
     * @param sessionKey User session token.
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Synchronize subscriptions between the Customer Center
     *             and the SUSE Manager database.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer synchronizeSubscriptions(String sessionKey) throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        ContentSyncManager csm = new ContentSyncManager();
        csm.updateSubscriptions(csm.getSubscriptions());
        return BaseHandler.VALID;
    }

    /**
     * Add a new channel to the SUSE Manager database.
     *
     * @param sessionKey user session token
     * @param channelLabel label of the channel to add
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Add a new channel to the SUSE Manager database
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.param #param_desc("string", "channelLabel", "Label of the channel to add")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer addChannel(String sessionKey, String channelLabel)
            throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        ContentSyncManager csm = new ContentSyncManager();
        csm.addChannel(channelLabel, csm.getRepositories());
        return BaseHandler.VALID;
    }

    /**
     * Migrate this SUSE Manager server to work with SCC.
     *
     * @param sessionKey user session token
     * @return Integer
     * @throws ContentSyncException
     *
     * @xmlrpc.doc Migrate this SUSE Manager server to work with SCC.
     * @xmlrpc.param #param_desc("string", "sessionKey", "Session token, issued at login")
     * @xmlrpc.returntype #return_int_success()
     */
    public Integer performMigration(String sessionKey) throws ContentSyncException {
        BaseHandler.getLoggedInUser(sessionKey);
        SUSEProductFactory.clearAllProducts();
        try {
            FileUtils.touch(new File(ContentSyncManager.SCC_MIGRATED));
        }
        catch (IOException e) {
            throw new ContentSyncException(e);
        }
        return BaseHandler.VALID;
    }
}
