/**
 * Copyright (c) 2018 SUSE LLC
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
package com.suse.manager.webui.services;

import static com.suse.manager.webui.services.SaltConstants.SUMA_STATE_FILES_ROOT_PATH;
import static com.suse.manager.webui.services.SaltServerActionService.PACKAGES_PKGINSTALL;

import com.redhat.rhn.domain.action.ActionChain;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactory;

import com.suse.manager.webui.utils.AbstractSaltRequisites;
import com.suse.manager.webui.utils.IdentifiableSaltState;
import com.suse.manager.webui.utils.SaltModuleRun;
import com.suse.manager.webui.utils.SaltPkgInstalled;

import com.suse.manager.webui.utils.SaltState;
import com.suse.manager.webui.utils.SaltSystemReboot;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Service to manage the Salt Action Chains generated by Suse Manager.
 */
public class SaltActionChainGeneratorService {

    /** Logger */
    private static final Logger LOG = Logger.getLogger(SaltActionChainGeneratorService.class);

    // Singleton instance of this class
    public static final SaltActionChainGeneratorService INSTANCE = new SaltActionChainGeneratorService();

    public static final String ACTION_STATE_ID_PREFIX = "mgr_actionchain_";
    public static final String ACTION_STATE_ID_ACTION_PREFIX = "_action_";
    public static final String ACTION_STATE_ID_CHUNK_PREFIX = "_chunk_";
    public static final String ACTIONCHAIN_SLS_FOLDER = "actionchains";

    private static final String ACTIONCHAIN_SLS_FILE_PREFIX = "actionchain_";
    private static final String SCRIPTS_DIR = "scripts";

    public static final Pattern ACTION_STATE_PATTERN =
            Pattern.compile(".*\\|-" + ACTION_STATE_ID_PREFIX + "(\\d+)" +
                    ACTION_STATE_ID_ACTION_PREFIX + "(\\d+)" +
                    ACTION_STATE_ID_CHUNK_PREFIX + "(\\d+).*");

    private Path suseManagerStatesFilesRoot;
    private boolean skipSetOwner;

    /**
     * Default constructor.
     */
    public SaltActionChainGeneratorService() {
        suseManagerStatesFilesRoot = Paths.get(SUMA_STATE_FILES_ROOT_PATH);
    }

    /**
     * Generates SLS files for an Action Chain.
     * @param actionChain the chain
     * @param minion a minion to execute the chain on
     * @param states a list of states
     */
    public void createActionChainSLSFiles(ActionChain actionChain, MinionServer minion, List<SaltState> states) {
        int chunk = 1;
        List<SaltState> fileStates = new LinkedList<>();
        for (SaltState state: states) {
            if (state instanceof AbstractSaltRequisites) {
                prevRequisiteRef(fileStates).ifPresent(ref -> {
                    ((AbstractSaltRequisites)state).addRequire(ref.getKey(), ref.getValue());
                });
            }
            if (state instanceof IdentifiableSaltState) {
                IdentifiableSaltState modRun = (IdentifiableSaltState)state;
                modRun.setId(modRun.getId() + ACTION_STATE_ID_CHUNK_PREFIX + chunk);
            }
            if (mustSplit(state)) {
                if (isSaltUpgrade(state)) {
                    fileStates.add(endChunk(actionChain, chunk, prevRequisiteRef(fileStates)));
                    fileStates.add(state);
                    fileStates.add(stopIfPreviousFailed(prevRequisiteRef(fileStates)));
                    saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
                    fileStates.clear();
                    chunk++;
                    fileStates.add(checkSaltUpgradeChunk(state));
                }
                else {
                    fileStates.add(state);
                    fileStates.add(endChunk(actionChain, chunk, prevRequisiteRef(fileStates)));
                    saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
                    chunk++;
                    fileStates.clear();
                }
            }
            else {
                fileStates.add(state);
            }
        }
        saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
    }

    private SaltState endChunk(ActionChain actionChain, int chunk, Optional<Pair<String, String>> lastRef) {
        Map<String, Object> args = new LinkedHashMap<>(2);
        args.put("actionchain_id", actionChain.getId());
        args.put("chunk", chunk + 1);
        SaltModuleRun modRun = new SaltModuleRun("schedule_next_chunk", "mgractionchains.next", args);
        lastRef.ifPresent(ref -> modRun.addRequire(ref.getKey(), ref.getValue()));
        return modRun;
    }

    private SaltState stopIfPreviousFailed(Optional<Pair<String, String>> lastRef) {
        Map<String, Object> args = new LinkedHashMap<>(1);
        Map<String, String> onFailedEntry = new LinkedHashMap<>(1);
        List<Object> onFailedList = new ArrayList<>();
        lastRef.ifPresent(ref -> {
            onFailedEntry.put(ref.getKey(), ref.getValue());
            onFailedList.add(onFailedEntry);
            args.put("onfail", onFailedList);
        });
        SaltModuleRun modRun =
                new SaltModuleRun("clean_action_chain_if_previous_failed",
                        "mgractionchains.clean", args);
        return modRun;
    }

    private SaltState checkSaltUpgradeChunk(SaltState state) {
        SaltModuleRun moduleRun = (SaltModuleRun) state;
        Map<String, Map<String, String>> paramPkgs =
                (Map<String, Map<String, String>>) moduleRun.getKwargs().get("pillar");
        SaltPkgInstalled pkgInstalled = new SaltPkgInstalled();
        for (Map.Entry<String, String> entry : paramPkgs.get("param_pkgs").entrySet()) {
            pkgInstalled.addPackage(entry.getKey(), entry.getValue());
        }
        return pkgInstalled;
    }

    /**
     * Get a requisite reference to the last state in the list.
     * @param fileStates salt state list
     * @return a requisite reference
     */
    private Optional<Pair<String, String>> prevRequisiteRef(List<SaltState> fileStates) {
        if (fileStates.size() > 0) {
            SaltState previousState = fileStates.get(fileStates.size() - 1);
            return previousState.getData().entrySet().stream().findFirst().map(entry ->
                ((Map<String, ?>)entry.getValue()).entrySet().stream().findFirst().map(ent -> {
                    String[] stateMod = ent.getKey().split("\\.");
                    if (stateMod.length == 2) {
                        return stateMod[0];
                    }
                    else {
                        throw new RuntimeException("Could not get Salt requisite reference for " + ent.getKey());
                    }
                })
                        .map(mod -> new ImmutablePair<>(mod, entry.getKey()))
                        .orElseThrow(() ->
                                new RuntimeException("Could not get Salt requisite reference for " + entry.getKey()))
            );
        }
        return Optional.empty();
    }

    private boolean isSaltUpgrade(SaltState state) {
        if (state instanceof SaltModuleRun) {
            SaltModuleRun moduleRun = (SaltModuleRun)state;

            Optional<String> mods = getModsString(moduleRun);

            if (mods.isPresent() &&
                    mods.get().contains(PACKAGES_PKGINSTALL)) {
                if (moduleRun.getKwargs() != null) {
                    Map<String, Map<String, String>> paramPkgs =
                            (Map<String, Map<String, String>>) moduleRun.getKwargs().get("pillar");
                    if (!paramPkgs.get("param_pkgs").entrySet().stream()
                            .filter(entry -> entry.getKey().equals("salt"))
                            .map(entry -> entry.getKey())
                            .collect(Collectors.toList()).isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean mustSplit(SaltState state) {
        boolean split = false;
        if (state instanceof SaltModuleRun) {
            SaltModuleRun moduleRun = (SaltModuleRun)state;

            Optional<String> mods = getModsString(moduleRun);

            if (mods.isPresent() &&
                    mods.get().contains(PACKAGES_PKGINSTALL) && isSaltUpgrade(state)) {
                split = true;
            }
            if ("system.reboot".equalsIgnoreCase(moduleRun.getName())) {
                split = true;
            }
        }
        else if (state instanceof SaltSystemReboot) {
            split = true;
        }
        return split;
    }

    private Optional<String> getModsString(SaltModuleRun moduleRun) {
        if (moduleRun.getArgs() == null) {
            return Optional.empty();
        }
        if (moduleRun.getArgs().get("mods") instanceof String) {
            return Optional.of((String)moduleRun.getArgs().get("mods"));
        }
        else if (moduleRun.getArgs().get("mods") instanceof List) {
            return Optional.of(((List)moduleRun.getArgs().get("mods"))
                    .stream()
                    .collect(Collectors.joining(","))
                    .toString());
        }
        return Optional.empty();
    }


    /**
     * Cleans up generated SLS files.
     * @param actionChainId an Action Chain ID
     * @param minionId a minion ID
     * @param chunk the chunk number
     * @param actionChainFailed whether the Action Chain failed or not
     */
    public void removeActionChainSLSFiles(Long actionChainId, String minionId, Integer chunk,
        Boolean actionChainFailed) {
        MinionServerFactory.findByMinionId(minionId).ifPresent(minionServer -> {
            Path targetDir = Paths.get(suseManagerStatesFilesRoot.toString(), ACTIONCHAIN_SLS_FOLDER);
            Path targetFilePath = Paths.get(targetDir.toString(),
                    getActionChainSLSFileName(actionChainId, minionServer, chunk));
            // Add specified SLS chunk file to remove list
            List<Path> filesToDelete = new ArrayList<>();
            filesToDelete.add(targetFilePath);
            // Add possible script files to remove list
            String filePattern = ACTIONCHAIN_SLS_FILE_PREFIX + actionChainId +
                    "_" + minionServer.getMachineId() + "_";
            try {
                if (actionChainFailed) {
                    // Add also next SLS chunks because the Action Chain failed and these
                    // files are not longer needed.
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, filePattern + "*.sls")) {
                        stream.forEach(slsFile -> {
                            try {
                                Files.deleteIfExists(slsFile);
                            }
                            catch (IOException e) {
                                LOG.error(e.getMessage(), e);
                            }
                        });
                    }
                }
            }
            catch (IOException e) {
                LOG.error(e.getMessage(), e);
            }
        });
    }

    /**
     * Generate file name for the action chain chunk file.
     * Public only for unit tests.
     *
     * @param actionChainId an Action Chain ID
     * @param minionServer a minion instance
     * @param chunk a chunk number
     * @return the file name
     */
    public String getActionChainSLSFileName(Long actionChainId, MinionServer minionServer, Integer chunk) {
        return (ACTIONCHAIN_SLS_FILE_PREFIX + Long.toString(actionChainId) +
                "_" + minionServer.getMachineId() + "_" + Integer.toString(chunk) + ".sls");
    }

    private void saveChunkSLS(List<SaltState> states, MinionServer minion, long actionChainId, int chunk) {
        Path targetDir = Paths.get(suseManagerStatesFilesRoot.toString(), ACTIONCHAIN_SLS_FOLDER);
        try {
            Files.createDirectories(targetDir);
            if (!skipSetOwner) {
                FileSystem fileSystem = FileSystems.getDefault();
                UserPrincipalLookupService service = fileSystem.getUserPrincipalLookupService();
                UserPrincipal tomcatUser = service.lookupPrincipalByName("tomcat");
                Files.setOwner(targetDir, tomcatUser);
            }
        } catch (IOException e) {
            LOG.error("Could not create action chain directory " + targetDir, e);
            throw new RuntimeException(e);
        }
        Path targetFilePath = Paths.get(targetDir.toString(),
                getActionChainSLSFileName(actionChainId, minion, chunk));

        try (Writer slsWriter = new FileWriter(targetFilePath.toFile());
             Writer slsBufWriter = new BufferedWriter(slsWriter)) {
            com.suse.manager.webui.utils.SaltStateGenerator saltStateGenerator =
                    new com.suse.manager.webui.utils.SaltStateGenerator(slsBufWriter);
            saltStateGenerator.generate(states.toArray(new SaltState[states.size()]));
        }
        catch (IOException e) {
            LOG.error("Could not write action chain sls " + targetFilePath, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * @param suseManagerStatesFilesRootIn to set
     */
    public void setSuseManagerStatesFilesRoot(Path suseManagerStatesFilesRootIn) {
        this.suseManagerStatesFilesRoot = suseManagerStatesFilesRootIn;
    }

    /**
     * Create the Salt state id string specific to the given action chain and action.
     *
     * @param actionChainId action chain id
     * @param actionId action id
     * @return state id string
     */
    public static String createStateId(long actionChainId, Long actionId) {
        return ACTION_STATE_ID_PREFIX + actionChainId +
                ACTION_STATE_ID_ACTION_PREFIX + actionId;
    }

    /**
     * Value class encapsulating the components of a state id
     * used in action chain state files.
     */
    public static final class ActionChainStateId {

        private long actionChainId;
        private long actionId;
        private int chunk;

        /**
         * @param actionChainIdIn action chain id
         * @param actionIdIn action id
         * @param chunkIn chunk number
         */
        public ActionChainStateId(long actionChainIdIn, long actionIdIn, int chunkIn) {
            this.actionChainId = actionChainIdIn;
            this.actionId = actionIdIn;
            this.chunk = chunkIn;
        }

        /**
         * @return actionChainId to get
         */
        public long getActionChainId() {
            return actionChainId;
        }

        /**
         * @return actionId to get
         */
        public long getActionId() {
            return actionId;
        }

        /**
         * @return chunk to get
         */
        public int getChunk() {
            return chunk;
        }
    }

    /**
     * Parse a Salt state id used in action chain sls files.
     * @param stateId the state id string
     * @return the action chain id, action id and chunk
     */
    public static Optional<ActionChainStateId> parseActionChainStateId(String stateId) {
        Matcher m = ACTION_STATE_PATTERN.matcher(stateId);
        if (m.find() && m.groupCount() == 3) {
            try {
                return Optional.of(
                        new ActionChainStateId(
                                Long.parseLong(m.group(1)),
                                Long.parseLong(m.group(2)),
                                Integer.parseInt(m.group(3))
                        )
                );
            }
            catch (NumberFormatException e) {
                LOG.error("Error parsing action chain state id: " + stateId, e);
            }
        }
        return Optional.empty();
    }

    /**
     * @param skipSetOwenerIn to set
     */
    public void setSkipSetOwner(boolean skipSetOwenerIn) {
        this.skipSetOwner = skipSetOwenerIn;
    }
}
