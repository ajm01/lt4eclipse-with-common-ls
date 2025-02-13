/*******************************************************************************
* Copyright (c) 2022 IBM Corporation and others.
*
* This program and the accompanying materials are made available under the
* terms of the Eclipse Public License v. 2.0 which is available at
* http://www.eclipse.org/legal/epl-2.0.
*
* SPDX-License-Identifier: EPL-2.0
*
* Contributors:
*     IBM Corporation - initial implementation
*******************************************************************************/
package io.openliberty.tools.eclipse;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWebBrowser;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;

import io.openliberty.tools.eclipse.logging.Trace;
import io.openliberty.tools.eclipse.ui.DashboardView;
import io.openliberty.tools.eclipse.ui.terminal.ProjectTab;
import io.openliberty.tools.eclipse.ui.terminal.ProjectTab.State;
import io.openliberty.tools.eclipse.ui.terminal.ProjectTabController;
import io.openliberty.tools.eclipse.utils.Dialog;

/**
 * Provides the implementation of all supported dev mode operations.
 */
public class DevModeOperations {

    // TODO: Dashboard display: Handle the case where the project is configured to be built/run by both
    // Gradle and Maven at the same time.

    /**
     * Constants.
     */
    public static final String DEVMODE_START_PARMS_DIALOG_TITLE = "Liberty Dev Mode";
    public static final String DEVMODE_START_PARMS_DIALOG_MSG = "Specify custom parameters for the liberty dev command.";
    public static final String BROWSER_MVN_IT_REPORT_NAME_SUFFIX = "failsafe report";
    public static final String BROWSER_MVN_UT_REPORT_NAME_SUFFIX = "surefire report";
    public static final String BROWSER_GRADLE_TEST_REPORT_NAME_SUFFIX = "test report";

    /**
     * Project terminal tab controller instance.
     */
    private ProjectTabController projectTabController;

    /**
     * Dashboard object reference.
     */
    private Dashboard dashboard;

    /**
     * PATH environment variable.
     */
    private String pathEnv;

    /**
     * Constructor.
     */
    public DevModeOperations() {
        projectTabController = ProjectTabController.getInstance();
        dashboard = new Dashboard();

        pathEnv = System.getenv("PATH");
    }

    /**
     * Returns true if the underlying OS is windows. False, otherwise.
     *
     * @return True if the underlying OS is windows. False, otherwise.
     */
    public boolean isWindows() {
        return System.getProperty("os.name").contains("Windows");
    }

    /**
     * Starts the server in dev mode.
     *
     * @return An error message or null if the command was processed successfully.
     */
    public void start() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_START
                    + " action. The object representing the selected project could not be found.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op.");
            }
            Dialog.displayErrorMessage(msg);
            return;
        }

        // Check if the start action has already been issued.
        String projectName = iProject.getName();
        State terminalState = projectTabController.getTerminalState(projectName);
        if (terminalState != null && terminalState == ProjectTab.State.STARTED) {
            // Check if the the terminal tab associated with this call was marked as closed. This scenario may occur if a previous
            // attempt to start the server in dev mode was issued successfully, but there was a failure in the process or
            // there was an unexpected case that caused the terminal process to end. If that is the case, clean up the objects
            // associated with the previous instance to allow users to restart dev mode.
            if (projectTabController.isProjectTabMarkedClosed(projectName)) {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "A start action on project " + projectName
                            + " was already issued, and the terminal tab for this project is marked as closed. Cleaning up. ProjectTabController: "
                            + projectTabController);
                }
                projectTabController.cleanupTerminal(projectName);
            } else {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "A start action has already been issued on project " + projectName
                            + ". No-op. ProjectTabController: " + projectTabController);
                }
                Dialog.displayWarningMessage("A start action has already been issued on project " + projectName + ". Select \""
                        + DashboardView.APP_MENU_ACTION_STOP + "\" prior to selecting \"" + DashboardView.APP_MENU_ACTION_START
                        + "\" on the menu.");
                return;
            }
        }

        Project project = null;

        try {
            project = dashboard.getProject(projectName);
            if (project == null) {
                throw new Exception("Unable to find internal instance of project with name: " + projectName);
            }

            // Get the absolute path to the application project.
            String projectPath = project.getPath();
            if (projectPath == null) {
                throw new Exception("Unable to find the path to selected project " + projectName);
            }

            // Prepare the Liberty plugin dev mode command.
            String cmd = "";
            if (project.getBuildType() == Project.BuildType.MAVEN) {
                cmd = getMavenCommand(projectPath, "io.openliberty.tools:liberty-maven-plugin:dev -f " + projectPath);
            } else if (project.getBuildType() == Project.BuildType.GRADLE) {
                cmd = getGradleCommand(projectPath, "libertyDev -p=" + projectPath);
            } else {
                throw new Exception("Project" + projectName + "is not a Gradle or Maven project.");
            }

            // Start a terminal and run the application in dev mode.
            startDevMode(cmd, projectName, projectPath);
        } catch (Exception e) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_START + " action on project "
                    + projectName;
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, project);
        }
    }

    /**
     * Starts the server in dev mode.
     *
     * @return An error message or null if the command was processed successfully.
     */
    public void startWithParms() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_START_PARMS
                    + " action. The object representing the selected project on the dashboard could not be found.";
            Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op.");
            Dialog.displayErrorMessage(msg);
            return;
        }

        String projectName = iProject.getName();

        // Check if the start action has already been issued.
        State terminalState = projectTabController.getTerminalState(projectName);
        if (terminalState != null && terminalState == ProjectTab.State.STARTED) {
            // Check if the the terminal tab associated with this call was marked as closed. This scenario may occur if a previous
            // attempt to start the server in dev mode was issued successfully, but there was a failure in the process or
            // there was an unexpected case that caused the terminal process to end. If that is the case, clean up the objects
            // associated with the previous instance to allow users to restart dev mode.
            if (projectTabController.isProjectTabMarkedClosed(projectName)) {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "A start action on project " + projectName
                            + " was already issued, and the terminal tab for this project is marked as closed. Cleaning up. ProjectTabController: "
                            + projectTabController);
                }
                projectTabController.cleanupTerminal(projectName);
            } else {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "A start action has already been issued on project " + projectName
                            + ". No-op. ProjectTabController: " + projectTabController);
                }
                Dialog.displayWarningMessage("A start action has already been issued on project " + projectName + ". Select \""
                        + DashboardView.APP_MENU_ACTION_STOP + "\" prior to selecting \"" + DashboardView.APP_MENU_ACTION_START_PARMS
                        + "\" on the menu.");
                return;
            }
        }

        Project project = null;

        try {
            project = dashboard.getProject(projectName);
            if (project == null) {
                throw new Exception("Unable to find internal instance of project with name: " + projectName);
            }

            // Get start parameters from the user. If the user cancelled or closed the parameter dialog,
            // take that as indication that no action should take place.
            String userParms = getStartParms();
            if (userParms == null) {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "Invalid user parms. No-op.");
                }
                return;
            }

            // Get the absolute path to the application project.
            String projectPath = project.getPath();
            if (projectPath == null) {
                throw new Exception("Unable to find the path to selected project " + projectName);
            }

            // Prepare the Liberty plugin dev mode command.
            String cmd = "";
            if (project.getBuildType() == Project.BuildType.MAVEN) {
                cmd = getMavenCommand(projectPath, "io.openliberty.tools:liberty-maven-plugin:dev " + userParms + " -f " + projectPath);
            } else if (project.getBuildType() == Project.BuildType.GRADLE) {
                cmd = getGradleCommand(projectPath, "libertyDev " + userParms + " -p=" + projectPath);
            } else {
                throw new Exception("Project" + projectName + "is not a Gradle or Maven project.");
            }

            // Start a terminal and run the application in dev mode.
            startDevMode(cmd, projectName, projectPath);
        } catch (Exception e) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_START_PARMS + " action on project "
                    + projectName + ".";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, project);
        }
    }

    /**
     * Starts the server in dev mode.
     *
     * @return An error message or null if the command was processed successfully.
     */
    public void startInContainer() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_START_IN_CONTAINER
                    + " action. The object representing the selected project on the dashboard could not be found.";
            if (Trace.isEnabled()) {
                Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, msg + " No-op.");
            }
            Dialog.displayErrorMessage(msg);
            return;
        }

        String projectName = iProject.getName();

        // Check if the start action has already been issued.
        State terminalState = projectTabController.getTerminalState(projectName);
        if (terminalState != null && terminalState == ProjectTab.State.STARTED) {
            // Check if the the terminal tab associated with this call was marked as closed. This scenario may occur if a previous
            // attempt to start the server in dev mode was issued successfully, but there was a failure in the process or
            // there was an unexpected case that caused the terminal process to end. If that is the case, clean up the objects
            // associated with the previous instance to allow users to restart dev mode.
            if (projectTabController.isProjectTabMarkedClosed(projectName)) {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "A start action on project " + projectName
                            + " was already issued, and the terminal tab for this project is marked as closed. Cleaning up. ProjectTabController: "
                            + projectTabController);
                }
                projectTabController.cleanupTerminal(projectName);
            } else {
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, "A start action has already been issued on project " + projectName
                            + ". No-op. ProjectTabController: " + projectTabController);
                }
                Dialog.displayWarningMessage("A start action has already been issued on project " + projectName + ". Select \""
                        + DashboardView.APP_MENU_ACTION_STOP + "\" prior to selecting \"" + DashboardView.APP_MENU_ACTION_START_IN_CONTAINER
                        + "\" on the menu.");
                return;
            }
        }

        Project project = null;

        try {
            project = dashboard.getProject(projectName);
            if (project == null) {
                throw new Exception("Unable to find internal instance of project with name: " + projectName);
            }

            // Get the absolute path to the application project.
            String projectPath = project.getPath();
            if (projectPath == null) {
                throw new Exception("Unable to find the path to selected project " + projectName);
            }

            // Prepare the Liberty plugin container dev mode command.
            String cmd = "";
            if (project.getBuildType() == Project.BuildType.MAVEN) {
                cmd = getMavenCommand(projectPath, "io.openliberty.tools:liberty-maven-plugin:devc -f " + projectPath);
            } else if (project.getBuildType() == Project.BuildType.GRADLE) {
                cmd = getGradleCommand(projectPath, "libertyDevc -p=" + projectPath);
            } else {
                throw new Exception("Project" + projectName + "is not a Gradle or Maven project.");
            }

            // Start a terminal and run the application in dev mode.
            startDevMode(cmd, projectName, projectPath);
        } catch (Exception e) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_START_IN_CONTAINER
                    + " action on project " + projectName + ".";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, project);
        }
    }

    /**
     * Starts the server in dev mode.
     *
     * @return An error message or null if the command was processed successfully.
     */
    public void stop() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_STOP
                    + " action. The object representing the selected project on the dashboard could not be found.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op.");
            }
            Dialog.displayErrorMessage(msg);
            return;
        }

        String projectName = iProject.getName();

        // Check if the stop action has already been issued of if a start action was never issued before.
        if (projectTabController.getProjectConnector(projectName) == null) {
            String msg = "A start action was not issued first or the stop action has already been issued on project " + projectName
                    + ". Select a start action on the menu prior to selecting the \"" + DashboardView.APP_MENU_ACTION_STOP + "\" action.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op. ProjectTabController: " + projectTabController);
            }
            Dialog.displayWarningMessage(msg);
            return;
        }

        // Check if the the terminal tab associated with this call was marked as closed. This scenario may occur if a previous
        // attempt to start the server in dev mode was issued successfully, but there was a failure in the process or
        // there was an unexpected case that caused the terminal process to end. Note that objects associated with the previous
        // start attempt will be cleaned up on the next restart attempt.
        if (projectTabController.isProjectTabMarkedClosed(projectName)) {
            String msg = "The terminal tab running project " + projectName
                    + " is not active due to an unexpected error or external action. Review the terminal output for more details. "
                    + "Once the circumstance that caused the terminal tab to be inactive is determined and resolved, "
                    + "select a start action on the menu prior to selecting the \"" + DashboardView.APP_MENU_ACTION_STOP + "\" action.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op. ProjectTabController: " + projectTabController);
            }
            Dialog.displayWarningMessage(msg);
            return;
        }

        try {
            // Prepare the dev mode command to stop the server.
            String cmd = "exit" + System.lineSeparator();

            // Issue the command on the terminal.
            projectTabController.writeTerminalStream(projectName, cmd.getBytes());

            // The command to exit dev mode was issued. Set the internal project tab state to STOPPED as
            // indication that the stop command was issued. The project's terminal tab UI will marked as closed (title and state
            // updates) when dev mode exits.
            projectTabController.setTerminalState(projectName, ProjectTab.State.STOPPED);

            // Cleanup internal objects. This maybe done a bit prematurely at this point because the operations triggered by
            // the action of writing to the terminal are asynchronous. However, there is no good way to listen for terminal tab
            // state changes (avoid loops or terminal internal class references). Furthermore, if errors are experienced during
            // devomde exit, those errors may not be easily solved by re-trying the stop command.
            // If there are any errors during cleanup or if cleanup does not happen at all here, clean up will be attempted
            // when the associated terminal view tab is closed/disposed.
            projectTabController.cleanupTerminal(projectName);

        } catch (Exception e) {
            String msg = "An error was detected while performing the stop action on project " + projectName + ".";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, projectName);
        }
    }

    /**
     * Runs the tests provided by the application.
     *
     * @return An error message or null if the command was processed successfully.
     */
    public void runTests() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_RUN_TESTS
                    + " action. The object representing the selected project on the dashboard could not be found.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op.");
            }
            Dialog.displayErrorMessage(msg);
            return;
        }

        String projectName = iProject.getName();

        // Check if the stop action has already been issued of if a start action was never issued before.
        if (projectTabController.getProjectConnector(projectName) == null) {
            String msg = "A start action was not issued first or the stop action has already been issued on project " + projectName
                    + ". Select a start action on the menu prior to selecting the \"" + DashboardView.APP_MENU_ACTION_RUN_TESTS
                    + "\" action.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op. ProjectTabController: " + projectTabController);
            }
            Dialog.displayWarningMessage(msg);
            return;
        }

        // Check if the the terminal tab associated with this call was marked as closed. This scenario may occur if a previous
        // attempt to start the server in dev mode was issued successfully, but there was a failure in the process or
        // there was an unexpected case that caused the terminal process to end. Note that objects associated with the previous
        // start attempt will be cleaned up on the next restart attempt.
        if (projectTabController.isProjectTabMarkedClosed(projectName)) {
            String msg = "The terminal tab running project " + projectName
                    + " is not active due to an unexpected error or external action. Review the terminal output for more details. "
                    + "Once the circumstance that caused the terminal tab to be inactive is determined and resolved, "
                    + "select a start action on the menu prior to selecting the \"" + DashboardView.APP_MENU_ACTION_RUN_TESTS
                    + "\" action.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op. ProjectTabController: " + projectTabController);
            }
            Dialog.displayWarningMessage(msg);
            return;
        }

        try {
            // Prepare the dev mode command to run a test.
            String cmd = System.lineSeparator();

            // Issue the command on the terminal.
            projectTabController.writeTerminalStream(projectName, cmd.getBytes());
        } catch (Exception e) {
            String msg = "An error was detected while performing the run tests action on project " + projectName + ".";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, projectName);
        }
    }

    /**
     * Open Maven integration test report.
     */
    public void openMavenIntegrationTestReport() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_VIEW_MVN_IT_REPORT
                    + " action. The object representing the selected project on the dashboard could not be found.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op.");
            }
            Dialog.displayErrorMessage(msg);
            return;
        }

        String projectName = iProject.getName();
        Project project = null;

        try {
            project = dashboard.getProject(projectName);
            if (project == null) {
                throw new Exception("Unable to find internal instance of project with name: " + projectName);
            }

            // Get the absolute path to the application project.
            String projectPath = project.getPath();
            if (projectPath == null) {
                throw new Exception("Unable to find the path to selected project " + projectName);
            }

            // Get the path to the test report.
            Path path = getMavenIntegrationTestReportPath(projectPath);
            if (!path.toFile().exists()) {
                String msg = "Integration test results were not found for project " + projectName + ". Select \""
                        + DashboardView.APP_MENU_ACTION_RUN_TESTS + "\" prior to selecting \""
                        + DashboardView.APP_MENU_ACTION_VIEW_MVN_IT_REPORT + "\" on the menu.";
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op. Path: " + path);
                }
                Dialog.displayWarningMessage(msg);
                return;
            }

            // Display the report on the browser. Browser display is based on eclipse configuration preferences.
            String browserTabTitle = projectName + " " + BROWSER_MVN_IT_REPORT_NAME_SUFFIX;
            openTestReport(projectName, path, path.toString(), browserTabTitle, browserTabTitle);
        } catch (Exception e) {
            String msg = "An error was detected while opening integration test report for project " + projectName + ".";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, project);
        }
    }

    /**
     * Open Maven unit test report.
     */
    public void openMavenUnitTestReport() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            if (Trace.isEnabled()) {
                Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
            }
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_VIEW_MVN_UT_REPORT
                    + " action. The object representing the selected project on the dashboard could not be found.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op.");
            }
            Dialog.displayErrorMessage(msg);
        }

        String projectName = iProject.getName();
        Project project = null;

        try {
            project = dashboard.getProject(projectName);
            if (project == null) {
                throw new Exception("Unable to find internal instance of project with name: " + projectName);
            }

            // Get the absolute path to the application project.
            String projectPath = project.getPath();
            if (projectPath == null) {
                throw new Exception("Unable to find the path to selected project " + projectName);
            }

            // Get the path to the test report.
            Path path = getMavenUnitTestReportPath(projectPath);
            if (!path.toFile().exists()) {
                String msg = "Unit test results were not found for project " + projectName + ". Select \""
                        + DashboardView.APP_MENU_ACTION_RUN_TESTS + "\" prior to selecting \""
                        + DashboardView.APP_MENU_ACTION_VIEW_MVN_UT_REPORT + "\" on the menu.";
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op. Path: " + path);
                }
                Dialog.displayWarningMessage(msg);
                return;
            }

            // Display the report on the browser. Browser display is based on eclipse configuration preferences.
            String browserTabTitle = projectName + " " + BROWSER_MVN_UT_REPORT_NAME_SUFFIX;
            openTestReport(projectName, path, path.toString(), browserTabTitle, browserTabTitle);
        } catch (Exception e) {
            String msg = "An error was detected while opening unit test report for project " + projectName + ".";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, project);
        }
    }

    /**
     * Open Gradle test report.
     */
    public void openGradleTestReport() {
        // Get the object representing the selected application project. The returned project should never be null, but check it
        // just in case it is.
        IProject iProject = getSelectedDashboardProject();

        if (Trace.isEnabled()) {
            Trace.getTracer().traceEntry(Trace.TRACE_TOOLS, iProject);
        }

        if (iProject == null) {
            String msg = "An error was detected while performing the " + DashboardView.APP_MENU_ACTION_VIEW_GRADLE_TEST_REPORT
                    + " action. The object representing the selected project on the dashboard could not be found.";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op.");
            }
            Dialog.displayErrorMessage(msg);
            return;
        }

        String projectName = iProject.getName();
        Project project = null;

        try {
            project = dashboard.getProject(projectName);
            if (project == null) {
                throw new Exception("Unable to find internal instance of project with name: " + projectName);
            }

            // Get the absolute path to the application project.
            String projectPath = project.getPath();
            if (projectPath == null) {
                throw new Exception("Unable to find the path to selected project " + projectName);
            }

            // Get the path to the test report.
            Path path = getGradleTestReportPath(projectPath);
            if (!path.toFile().exists()) {
                String msg = "Test results were not found for project " + projectName + ". Select \""
                        + DashboardView.APP_MENU_ACTION_RUN_TESTS + "\" prior to selecting \""
                        + DashboardView.APP_MENU_ACTION_VIEW_GRADLE_TEST_REPORT + "\" on the menu.";
                if (Trace.isEnabled()) {
                    Trace.getTracer().trace(Trace.TRACE_TOOLS, msg + " No-op. Path: " + path);
                }
                Dialog.displayWarningMessage(msg);
                return;
            }

            // Display the report on the browser. Browser display is based on eclipse configuration preferences.
            String browserTabTitle = projectName + " " + BROWSER_GRADLE_TEST_REPORT_NAME_SUFFIX;
            openTestReport(projectName, path, path.toString(), browserTabTitle, browserTabTitle);
        } catch (Exception e) {
            String msg = "An error was detected while opening test report for project " + projectName + ".";
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS, msg, e);
            }
            Dialog.displayErrorMessageWithDetails(msg, e);
            return;
        }

        if (Trace.isEnabled()) {
            Trace.getTracer().traceExit(Trace.TRACE_TOOLS, project);
        }
    }

    /**
     * Opens the specified report in a browser.
     *
     * @param projectName The application project name.
     * @param path The path to the HTML report file.
     * @param browserId The Id to use for the browser display.
     * @param name The name to use for the browser display.
     * @param toolTip The tool tip to use for the browser display.
     *
     * @throws Exception If an error occurs while displaying the test report.
     */
    public void openTestReport(String projectName, Path path, String browserId, String name, String toolTip) throws Exception {
        URL url = path.toUri().toURL();
        IWorkbenchBrowserSupport bSupport = PlatformUI.getWorkbench().getBrowserSupport();
        IWebBrowser browser = null;
        if (bSupport.isInternalWebBrowserAvailable()) {
            browser = bSupport.createBrowser(IWorkbenchBrowserSupport.AS_EDITOR | IWorkbenchBrowserSupport.LOCATION_BAR
                    | IWorkbenchBrowserSupport.NAVIGATION_BAR | IWorkbenchBrowserSupport.STATUS, browserId, name, toolTip);
        } else {
            browser = bSupport.createBrowser(browserId);
        }

        browser.openURL(url);
    }

    /**
     * Runs the specified command on a terminal.
     *
     * @param cmd The command to run.
     * @param projectName The name of the project currently being processed.
     * @param projectPath The project's path.
     *
     * @throws Exception If an error occurs while running the specified command.
     */
    public void startDevMode(String cmd, String projectName, String projectPath) throws Exception {
        List<String> envs = new ArrayList<String>(1);
        envs.add("JAVA_HOME=" + getJavaInstallHome());

        // Required on windows to work with mvnw.cmd
        if (isWindows()) {
            envs.add("MAVEN_BASEDIR=" + projectPath);
        }

        projectTabController.runOnTerminal(projectName, projectPath, cmd, envs);
    }

    /**
     * Returns the list of parameters if the user presses OK, null otherwise.
     *
     * @return The list of parameters if the user presses OK, null otherwise.
     */
    public String getStartParms() {
        String dInitValue = "";
        IInputValidator iValidator = getParmListValidator();
        Shell shell = Display.getCurrent().getActiveShell();
        InputDialog iDialog = new InputDialog(shell, DEVMODE_START_PARMS_DIALOG_TITLE, DEVMODE_START_PARMS_DIALOG_MSG, dInitValue,
                iValidator) {
        };

        String userInput = null;

        if (iDialog.open() == Window.OK) {
            userInput = iDialog.getValue().trim();
        } else {
            if (Trace.isEnabled()) {
                Trace.getTracer().trace(Trace.TRACE_TOOLS,
                        "The input parm dialog exited without the OK button being pressed. No values were retrieved.");
            }
        }

        return userInput;
    }

    /**
     * Creates a validation object for user provided parameters.
     *
     * @return A validation object for user provided parameters.
     */
    public IInputValidator getParmListValidator() {
        return new IInputValidator() {

            @Override
            public String isValid(String text) {
                return null;
            }
        };
    }

    /**
     * Returns the home path to the Java installation.
     *
     * @return The home path to the Java installation, or null if not found.
     */
    private String getJavaInstallHome() {
        String javaHome = null;
        // TODO: 1. Find the eclipse->java configured install path

        // 2. Check for associated environment variable.
        if (javaHome == null) {
            javaHome = System.getenv("JAVA_HOME");
        }

        // 3. Check for associated system properties.
        if (javaHome == null) {
            javaHome = System.getProperty("java.home");
        }

        return javaHome;
    }

    /**
     * Returns the full Maven command to run on the terminal.
     *
     * @param projectPath The project's path.
     * @param cmdArgs The mvn command args
     *
     * @return The full Maven command to run on the terminal.
     */
    public String getMavenCommand(String projectPath, String cmdArgs) {
        String cmd = null;

        // Check if there is wrapper defined.
        Path p2mw = (isWindows()) ? Paths.get(projectPath, "mvnw.cmd") : Paths.get(projectPath, "mvnw");
        Path p2mwJar = Paths.get(projectPath, ".mvn", "wrapper", "maven-wrapper.jar");
        Path p2mwProps = Paths.get(projectPath, ".mvn", "wrapper", "maven-wrapper.properties");

        if (p2mw.toFile().exists() && p2mwJar.toFile().exists() && p2mwProps.toFile().exists()) {
            cmd = p2mw.toString();
        } else {
            cmd = getCmdFromPath(isWindows() ? "mvn.cmd" : "mvn");
        }

        return getCommandFromArgs(cmd, cmdArgs);
    }

    /**
     * Returns the full Gradle command to run on the terminal.
     *
     * @param projectPath The project's path.
     * @param cmdArgs The Gradle command arguments.
     *
     * @return The full Gradle command to run on the terminal.
     */
    public String getGradleCommand(String projectPath, String cmdArgs) {

        String cmd = null;

        // Check if there is wrapper defined.
        Path p2gw = (isWindows()) ? Paths.get(projectPath, "gradlew.bat") : Paths.get(projectPath, "gradlew");
        Path p2gwJar = Paths.get(projectPath, "gradle", "wrapper", "gradle-wrapper.jar");
        Path p2gwProps = Paths.get(projectPath, "gradle", "wrapper", "gradle-wrapper.properties");

        if (p2gw.toFile().exists() && p2gwJar.toFile().exists() && p2gwProps.toFile().exists()) {
            cmd = p2gw.toString();
        } else {
            cmd = getCmdFromPath(isWindows() ? "gradle.bat" : "gradle");
        }

        return getCommandFromArgs(cmd, cmdArgs);
    }

    private String getCommandFromArgs(String cmd, String cmdArgs) {
        // Put it all together.
        StringBuilder sb = new StringBuilder();
        if (cmd != null) {
            sb.append(cmd).append(" ").append(cmdArgs);
            if (isWindows()) {
                // Include trailing space for separation
                sb.insert(0, "/c ");
            }
        }

        return sb.toString();
    }

    private String getCmdFromPath(String cmd) throws IllegalStateException {

        String foundCmd = null;

        String[] pathMembers = pathEnv.split(File.pathSeparator);
        for (int s = 0; s < pathMembers.length; s++) {
            File tempFile = new File(pathMembers[s] + File.separator + cmd);

            if (tempFile.exists()) {
                foundCmd = tempFile.getPath();
                break;
            }
        }

        if (foundCmd == null) {
            throw new IllegalStateException("Couldn't find command: " + cmd + " on PATH env var");
        }

        return foundCmd;
    }

    /**
     * Returns the path of the HTML file containing the integration test report.
     *
     * @param projectPath The project's path.
     *
     * @return The path of the HTML file containing the integration test report.
     */
    public static Path getMavenIntegrationTestReportPath(String projectPath) {
        Path path = Paths.get(projectPath, "target", "site", "failsafe-report.html");

        return path;
    }

    /**
     * Returns the path of the HTML file containing the unit test report.
     *
     * @param projectPath The project's path.
     *
     * @return The path of the HTML file containing the unit test report.
     */
    public static Path getMavenUnitTestReportPath(String projectPath) {
        Path path = Paths.get(projectPath, "target", "site", "surefire-report.html");

        return path;
    }

    /**
     * Returns the path of the HTML file containing the test report.
     *
     * @param projectPath The project's path.
     *
     * @return The custom path of the HTML file containing the or the default location.
     */
    public static Path getGradleTestReportPath(String projectPath) {
        // TODO: Look for custom dir entry in build.gradle:
        // "test.reports.html.destination". Need to handle a value like this:
        // reports.html.destination = file("$buildDir/edsTestReports/teststuff")
        // Notice the use of a variable: $buildDir.

        // If a custom path was not defined, use default value.
        Path path = Paths.get(projectPath, "build", "reports", "tests", "test", "index.html");

        return path;
    }

    /**
     * Returns the project instance associated with the currently selected view object in the workspace.
     *
     * @return The project currently selected or null if one was not found.
     */
    public IProject getSelectedDashboardProject() {
        IProject iProject = null;
        IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        if (w != null) {
            ISelectionService selectionService = w.getSelectionService();
            ISelection selection = selectionService.getSelection();

            if (selection instanceof IStructuredSelection) {
                IStructuredSelection structuredSelection = (IStructuredSelection) selection;
                Object firstElement = structuredSelection.getFirstElement();
                if (firstElement instanceof String) {
                    Project project = dashboard.getProject((String) firstElement);
                    if (project != null) {
                        iProject = project.getIProject();
                    }

                }
            }
        }

        return iProject;
    }

    /**
     * Returns a sorted list of projects that are configured to run on Liberty.
     *
     * @return A sorted list of projects that are configured to run on Liberty.
     *
     * @throws Exception
     */
    public List<String> getDashboardProjects() throws Exception {
        dashboard.retrieveSupportedProjects();
        List<String> mvnProjs = dashboard.getMavenProjectNames();
        Collections.sort(mvnProjs);
        List<String> gradleProjs = dashboard.getGradleProjectNames();
        Collections.sort(gradleProjs);

        ArrayList<String> sortedProjects = new ArrayList<String>();
        sortedProjects.addAll(mvnProjs);
        sortedProjects.addAll(gradleProjs);
        return sortedProjects;
    }

    /**
     * Returns the project associated with the input name.
     * 
     * @param name The name of the project.
     * 
     * @return The project associated with the input name.
     */
    public Project getDashboardProject(String name) {
        return dashboard.getProject(name);
    }
}
