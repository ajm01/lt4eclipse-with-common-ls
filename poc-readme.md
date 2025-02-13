**How to build and run the lsp4mp / lsp4jdt common layer POC**

**First, Build the lsp4jdt-common-poc repo project**  
clone: `git@github.com:ajm01/lsp4jdt-common-poc.git`
1. From the command line in the root of the project issue "mvn clean install" 
Once completed there will be a lsp4jdt.core plugin produced - this is the common jdt poc plugin that will be needed to build the lsp4mp plugin which will make use of this common jdt for the bulk of its jdt interactions. It will also be used for the LT4Eclipse plugin to embed the lsp4mp LS and MP JDT.
The plugin will be located in the target dir of the org.eclipse.lsp4jdt.site sub project as a archive file or a repository that can be installed in the lsp4mp and LT4Eclipse eclipse env (via target-platform files in each)

**Second, Build the lsp4mp-common-poc repo project**  
clone: `git@github.com:ajm01/lsp4mp-common-poc.git`
1. import project into eclipse as an existing Maven Project (Import -> Maven -> Existing Maven Project) and edit the target platform file.
remove the `REPLACE_WITH_LSP4JDT_POC_SITE_DIRECTORY` entry (Remove button on the right) and replace it with a new software site (Add -> Software site, again on the right) that pulls in the snapshot jar that was built in the lsp4jdt common project (above, previous).
2. save (and reload if working in eclipse) this target platform
3. On the command line, issue `./buildAll.sh` from the parent dir of the project
this will run the tests...there will be a small number of test failures. This is expected as-is - but..the test failures will also prevent the artifacts from being constructed in the end.
4. To get the lsp4mp jar and the lsp4mp jdk plugin built you need to add `-DskipTests=true` to the two build commands contained in the buildAll.sh script.  
To do this manually:
  (from the parent proj directory)  
   a) copy + paste to the command prompt this  microprofile.jdt core build command : `cd microprofile.jdt && ./mvnw clean install -DskipTests=true && cd ..`  
   b) copy + paste to the command prompt this  microprofile.ls build command : `cd microprofile.ls/org.eclipse.lsp4mp.ls && ./mvnw clean install -DskipTests=true && cd ../..`  

Once completed there will be a lsp4mp jdt plugin zip archive and repository located in the target directory of microprofile.jdt/org.eclipse.lsp4mp.jdt.site dir.  
   
There will also be a lsp4mp LS jar file (containing the lsp4mp LS runtime code) located in the target dir under the microprofile.ls sub project. These two artifacts will be needed to incorporate the new common based lsp4mp into LT4Eclipse (next). Alternatively, the lsp4mp test suite can be run here both from the command line (./buildAll.sh or direct tests invocation) or from within eclipse (Run-As -> JUnit Plugin tests)

**Third, Integrate lsp4mp into LT4Eclipse**  
clone: `git@github.com:ajm01/lt4eclipse-with-common-ls.git`  

Import project into eclipse as an existing maven project (details on how to do this, above) and edit the `\liberty-tools-eclipse\releng\target-platform-3Q2024\target-platform-3Q2024.target` file  
1. Replace the `REPLACE_WITH_LSP4JDT_POC_SITE_DIRECTORY` entry with a new software site that pulls in the snapshot jar that was built in the lsp4jdt common (Task 1 above))  
2. Replace the `REPLACE_WITH_LSP4MP_POC_SITE_DIRECTORY` entry with a new software site that pulls in the snapshot jar that was built in the lsp4mp common (Task 2 above)
3. Refresh and reload this target profile file in eclipse  
4. Under `root-dir/liberty-tools-eclipse/bundles/io.openliberty.tools.eclipse.lsp4e/server/mp-langserver` directory delete the `org.eclipse.lsp4mp.ls.jar` file - it will be automatically replaced by org.eclipse.lsp4mp.ls.jar.old - this is ok.
5. Navigate to the lsp4mp project file system from task 2 above and copy the `org.eclipse.lsp4mp.ls-uber.jar` file from the lsp4mp project found under the microprofile.ls/target dir (built in the previous section) into the `root-dir/liberty-tools-eclipse/bundles/io.openliberty.tools.eclipse.lsp4e/server/mp-langserver` directory (in the LT4Eclipse project)
6. Add this jar from where it now resides to the build path config of the LT4Eclipse sub project root-directory/bundles/io.openliberty.tools.eclipse.lsp4e
to do this, right click on the `root-directory/bundles/io.openliberty.tools.eclipse.lsp4e` folder in Project Explorer, choose Build Path and add the jar to the classpath setting of the build path.
7. To run LT4Eclipse with the integrated lsp4mp, right click on `root-dir/bundles/io.openliberty.tools.eclipse.lsp4e` and choose Run-As->Eclipse Application. This will kick of a stand alone child eclipse that you can interact with and try lsp4mp stuff



