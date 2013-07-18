/*
 * Copyright (C) 2005-2013 ManyDesigns srl.  All rights reserved.
 * http://www.manydesigns.com/
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package com.manydesigns.portofino.actions.admin.page;

import com.manydesigns.elements.ElementsThreadLocals;
import com.manydesigns.elements.fields.Field;
import com.manydesigns.elements.fields.SelectField;
import com.manydesigns.elements.forms.Form;
import com.manydesigns.elements.forms.FormBuilder;
import com.manydesigns.elements.forms.TableForm;
import com.manydesigns.elements.forms.TableFormBuilder;
import com.manydesigns.elements.messages.SessionMessages;
import com.manydesigns.elements.options.DefaultSelectionProvider;
import com.manydesigns.elements.options.SelectionProvider;
import com.manydesigns.elements.text.OgnlTextFormat;
import com.manydesigns.elements.util.ElementsFileUtils;
import com.manydesigns.elements.util.RandomUtil;
import com.manydesigns.elements.util.ReflectionUtil;
import com.manydesigns.portofino.AppProperties;
import com.manydesigns.portofino.actions.forms.CopyPage;
import com.manydesigns.portofino.actions.forms.MovePage;
import com.manydesigns.portofino.actions.forms.NewPage;
import com.manydesigns.portofino.buttons.annotations.Button;
import com.manydesigns.portofino.buttons.annotations.Buttons;
import com.manydesigns.portofino.di.Inject;
import com.manydesigns.portofino.dispatcher.*;
import com.manydesigns.portofino.logic.SecurityLogic;
import com.manydesigns.portofino.modules.BaseModule;
import com.manydesigns.portofino.modules.PageActionsModule;
import com.manydesigns.portofino.pageactions.AbstractPageAction;
import com.manydesigns.portofino.pageactions.PageActionLogic;
import com.manydesigns.portofino.pageactions.registry.PageActionInfo;
import com.manydesigns.portofino.pageactions.registry.PageActionRegistry;
import com.manydesigns.portofino.pages.*;
import com.manydesigns.portofino.scripting.ScriptingUtil;
import com.manydesigns.portofino.security.AccessLevel;
import com.manydesigns.portofino.security.RequiresPermissions;
import com.manydesigns.portofino.security.SupportsPermissions;
import com.manydesigns.portofino.shiro.PortofinoRealm;
import com.manydesigns.portofino.shiro.ShiroUtils;
import com.manydesigns.portofino.stripes.ForbiddenAccessResolution;
import net.sourceforge.stripes.action.*;
import net.sourceforge.stripes.util.HttpUtil;
import ognl.OgnlContext;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.Serializable;
import java.util.*;

/**
 * @author Paolo Predonzani     - paolo.predonzani@manydesigns.com
 * @author Angelo Lupo          - angelo.lupo@manydesigns.com
 * @author Giampiero Granatella - giampiero.granatella@manydesigns.com
 * @author Alessio Stalla       - alessio.stalla@manydesigns.com
 */
@UrlBinding("/actions/admin/page")
public class PageAdminAction extends AbstractPageAction {
    public static final String copyright =
            "Copyright (c) 2005-2013, ManyDesigns srl";

    protected String originalPath;
    public Dispatch dispatch;

    private static final Logger logger = LoggerFactory.getLogger(PageAdminAction.class);

    @Inject(PageActionsModule.PAGES_DIRECTORY)
    public File pagesDir;

    //--------------------------------------------------------------------------
    // Page crud fields
    //--------------------------------------------------------------------------

    protected static final String[][] NEW_PAGE_SETUP_FIELDS = {
            {"actionClassName", "fragment", "title", "description", "insertPositionName"}};
    protected Form newPageForm;
    protected String title;

    protected final PageActionRegistry registry = new PageActionRegistry();

    @Before
    public Resolution prepare() {
        Dispatcher dispatcher = DispatcherUtil.get(context.getRequest());
        String contextPath = context.getRequest().getContextPath();
        dispatch = dispatcher.getDispatch(contextPath, originalPath);
        pageInstance = dispatch.getLastPageInstance();

        List<String> knownPageActions = portofinoConfiguration.getList("pageactions");
        for(String pageAction : knownPageActions) {
            tryToRegisterPageAction(pageAction);
        }
        knownPageActions = portofinoConfiguration.getList("pageactions");
        if(knownPageActions != null) {
            for(String pageAction : knownPageActions) {
                tryToRegisterPageAction(pageAction);
            }
        }

        if(!SecurityLogic.hasPermissions(
                portofinoConfiguration, pageInstance, SecurityUtils.getSubject(), AccessLevel.EDIT)) {
            return new ForbiddenAccessResolution();
        } else {
            return null;
        }
    }

    protected void tryToRegisterPageAction(String className) {
        try {
            registry.register(getActionClass(className));
        } catch (Exception e) {
            logger.warn("{} class not found, page not available", className);
        }
    }

    protected Class<?> getActionClass(String className) throws ClassNotFoundException {
        ClassLoader classLoader = (ClassLoader) context.getServletContext().getAttribute(BaseModule.CLASS_LOADER);
        return Class.forName(className, true, classLoader);
    }

    //--------------------------------------------------------------------------
    // Page crud
    //--------------------------------------------------------------------------

    @Buttons({
        @Button(list = "page-children-edit", key = "commons.cancel", order = 99),
        @Button(list = "page-permissions-edit", key = "commons.cancel", order = 99),
        @Button(list = "page-create", key = "commons.cancel", order = 99)
    })
    public Resolution cancel() {
        return new RedirectResolution(dispatch.getOriginalPath());
    }

    public Resolution updateLayout() {
        HttpServletRequest request = context.getRequest();
        Enumeration parameters = request.getParameterNames();
        while(parameters.hasMoreElements()) {
            String parameter = (String) parameters.nextElement();
            if(parameter.startsWith("portletWrapper_")) {
                String layoutContainer = parameter.substring("portletWrapper_".length());
                String[] portletWrapperIds = request.getParameterValues(parameter);
                try {
                    updateLayout(layoutContainer, portletWrapperIds);
                } catch (Exception e) {
                    logger.error("Error updating layout", e);
                    SessionMessages.addErrorMessage(ElementsThreadLocals.getText("layout.update.failed"));
                }
            }
        }
        return new RedirectResolution(dispatch.getOriginalPath());
    }

    protected void updateLayout(String layoutContainer, String[] portletWrapperIds) throws Exception {
        PageInstance instance = getPageInstance();
        Layout layout = instance.getLayout();
        if(layout == null) {
            layout = new Layout();
            instance.setLayout(layout);
        }
        for(int i = 0; i < portletWrapperIds.length; i++) {
            String current = portletWrapperIds[i];
            if (current.startsWith("c")) { //Retaggio di quando avevamo self (non serve piu')
                String pageFragment = current.substring(1); //current = c...
                for(ChildPage p : layout.getChildPages()) {
                    if(pageFragment.equals(p.getName())) {
                        p.setContainer(layoutContainer);
                        p.setOrder(i + "");
                    }
                }
            } else {
                logger.debug("Ignoring: {}", current);
            }
        }
        DispatcherLogic.savePage(instance);
    }

    public Resolution newPage() throws Exception {
        prepareNewPageForm();
        return new ForwardResolution("/layouts/page-crud/new-page.jsp");
    }

    @Button(list = "page-create", key = "commons.create", order = 1, type = Button.TYPE_PRIMARY)
    @RequiresPermissions(level = AccessLevel.EDIT)
    public Resolution createPage() {
        try {
            return doCreateNewPage();
        } catch (Exception e) {
            logger.error("Error creating page", e);
            String msg = ElementsThreadLocals.getText("page.create.failed", e.getMessage());
            SessionMessages.addErrorMessage(msg);
            return new ForwardResolution("/layouts/page-crud/new-page.jsp");
        }
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public static enum InsertPosition {
        TOP, CHILD, SIBLING
    }

    private Resolution doCreateNewPage() throws Exception {
        prepareNewPageForm();
        if(newPageForm.validate()) {
            NewPage newPage = new NewPage();
            newPageForm.writeToObject(newPage);
            InsertPosition insertPosition =
                    InsertPosition.valueOf(newPage.getInsertPositionName());
            String pageClassName = newPage.getActionClassName();
            Class actionClass = getActionClass(pageClassName);
            PageActionInfo info = registry.getInfo(actionClass);
            String scriptTemplate = info.scriptTemplate;
            Class<?> configurationClass = info.configurationClass;
            boolean supportsDetail = info.supportsDetail;

            String pageId = RandomUtil.createRandomId();
            String className = pageId;
            if(Character.isDigit(className.charAt(0))) {
                className = "_" + className;
            }
            OgnlContext ognlContext = ElementsThreadLocals.getOgnlContext();
            ognlContext.put("generatedClassName", className);
            ognlContext.put("pageClassName", pageClassName);
            String script = OgnlTextFormat.format(scriptTemplate, this);

            Page page = new Page();
            BeanUtils.copyProperties(page, newPage);
            page.setId(pageId);

            Object configuration = null;
            if(configurationClass != null) {
                configuration = ReflectionUtil.newInstance(configurationClass);
                if(configuration instanceof ConfigurationWithDefaults) {
                    ((ConfigurationWithDefaults) configuration).setupDefaults();
                }
            }
            page.init();

            String fragment = newPage.getFragment();
            String configurePath;
            File directory;
            File parentDirectory;
            Page parentPage;
            PageInstance parentPageInstance;
            Layout parentLayout;
            switch (insertPosition) {
                case TOP:
                    parentDirectory = pagesDir;
                    directory = new File(parentDirectory, fragment);
                    parentPage = DispatcherLogic.getPage(parentDirectory);
                    parentLayout = parentPage.getLayout();
                    configurePath = "";
                    parentPageInstance = new PageInstance(null, parentDirectory, parentPage, null);
                    break;
                case CHILD:
                    parentPageInstance = getPageInstance();
                    parentPage = parentPageInstance.getPage();
                    parentLayout = parentPageInstance.getLayout();
                    parentDirectory = parentPageInstance.getDirectory();
                    directory = parentPageInstance.getChildPageDirectory(fragment);
                    configurePath = dispatch.getOriginalPath();
                    break;
                case SIBLING:
                    parentPageInstance = dispatch.getPageInstance(-2);
                    parentPage = parentPageInstance.getPage();
                    parentLayout = parentPageInstance.getLayout();
                    parentDirectory = parentPageInstance.getDirectory();
                    directory = parentPageInstance.getChildPageDirectory(fragment);
                    configurePath = parentPageInstance.getPath();
                    break;
                default:
                    throw new IllegalStateException("Don't know how to add page " + page + " at position " + insertPosition);
            }

            //Check permissions on target parent
            Subject subject = SecurityUtils.getSubject();
            if(!SecurityLogic.hasPermissions(portofinoConfiguration, parentPageInstance, subject, AccessLevel.EDIT)) {
                logger.warn("User not authorized to create page.");
                return new ForbiddenAccessResolution("You are not authorized to create a new page here.");
            }

            if(directory.exists()) {
                logger.error("Can't create page - directory {} exists", directory.getAbsolutePath());
                SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.create.failed.directoryExists"));
                return new ForwardResolution("/layouts/page-crud/new-page.jsp");
            }
            if(ElementsFileUtils.safeMkdirs(directory)) {
                try {
                    page.getLayout().setTemplate(parentPage.getLayout().getTemplate());
                    page.getDetailLayout().setTemplate(parentPage.getDetailLayout().getTemplate());
                    logger.debug("Creating the new child page in directory: {}", directory);
                    DispatcherLogic.savePage(directory, page);
                    if(configuration != null) {
                        DispatcherLogic.saveConfiguration(directory, configuration);
                    }
                    File groovyScriptFile =
                        ScriptingUtil.getGroovyScriptFile(directory, "action");
                    FileWriter fw = null;
                    try {
                        fw = new FileWriter(groovyScriptFile);
                        fw.write(script);
                    } finally {
                        IOUtils.closeQuietly(fw);
                    }
                    logger.debug("Registering the new child page in parent page (directory: {})", parentDirectory);
                    ChildPage childPage = new ChildPage();
                    childPage.setName(directory.getName());
                    childPage.setShowInNavigation(true);
                    parentLayout.getChildPages().add(childPage);

                    if(supportsDetail) {
                        File detailDir = new File(directory, PageInstance.DETAIL);
                        logger.debug("Creating _detail directory: {}", detailDir);
                        if(!ElementsFileUtils.safeMkdir(detailDir)) {
                            logger.warn("Couldn't create detail directory {}", detailDir);
                        }
                    }

                    DispatcherLogic.savePage(parentDirectory, parentPage);
                } catch (Exception e) {
                    logger.error("Exception saving page configuration");
                    SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.create.failed"));
                    return new ForwardResolution("/layouts/page-crud/new-page.jsp");
                }
            } else {
                logger.error("Can't create directory {}", directory.getAbsolutePath());
                SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.create.failed.cantCreateDir"));
                return new ForwardResolution("/layouts/page-crud/new-page.jsp");
            }
            logger.info("Page " + pageId + " created. Path: " + directory.getAbsolutePath());
            SessionMessages.addInfoMessage(ElementsThreadLocals.getText("page.create.successful"));
            String url = context.getRequest().getContextPath() + configurePath + "/" + fragment;
            return new RedirectResolution(url, false)
                            .addParameter("configure").addParameter("cancelReturnUrl", url);
        } else {
            return new ForwardResolution("/layouts/page-crud/new-page.jsp");
        }
    }

    public Resolution deletePage() {
        PageInstance pageInstance = getPageInstance();
        PageInstance parentPageInstance = pageInstance.getParent();
        if(parentPageInstance == null) {
            SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.delete.forbidden.root"));
        } else {
            Dispatcher dispatcher = DispatcherUtil.get(context.getRequest());
            String contextPath = context.getRequest().getContextPath();
            String landingPagePath = portofinoConfiguration.getString(AppProperties.LANDING_PAGE);
            Dispatch landingPageDispatch = dispatcher.getDispatch(contextPath, landingPagePath);
            if(landingPageDispatch != null &&
               landingPageDispatch.getLastPageInstance().getDirectory().equals(pageInstance.getDirectory())) {
                SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.delete.forbidden.landing"));
                return new RedirectResolution(dispatch.getOriginalPath());
            }
            try {
                String pageName = pageInstance.getName();
                File childPageDirectory = parentPageInstance.getChildPageDirectory(pageName);
                Layout parentLayout = parentPageInstance.getLayout();
                Iterator<ChildPage> it = parentLayout.getChildPages().iterator();
                while(it.hasNext()) {
                    if(pageName.equals(it.next().getName())) {
                        it.remove();
                        DispatcherLogic.savePage(parentPageInstance.getDirectory(), parentPageInstance.getPage());
                        break;
                    }
                }
                FileUtils.deleteDirectory(childPageDirectory);
            } catch (Exception e) {
                logger.error("Error deleting page", e);
            }
            return new RedirectResolution(StringUtils.defaultIfEmpty(parentPageInstance.getPath(), "/"));
        }
        return new RedirectResolution(dispatch.getOriginalPath());
    }

    public Page getPage() {
        return getPageInstance().getPage();
    }

    public PageInstance getPageInstance() {
        return dispatch.getLastPageInstance();
    }

    public Resolution movePage() {
        buildMovePageForm();
        moveForm.readFromRequest(context.getRequest());
        if(moveForm.validate()) {
            MovePage p = new MovePage();
            moveForm.writeToObject(p);
            return copyPage(p.destinationPagePath, null, true);
        } else {
            Field field = (Field) moveForm.get(0).get(0);
            if(!field.getErrors().isEmpty()) {
                SessionMessages.addErrorMessage(field.getLabel() + ": " + field.getErrors().get(0));
            }
            return new RedirectResolution(dispatch.getOriginalPath());
        }
    }

    public Resolution copyPage() {
        buildCopyPageForm();
        copyForm.readFromRequest(context.getRequest());
        if(copyForm.validate()) {
            CopyPage p = new CopyPage();
            copyForm.writeToObject(p);
            return copyPage(p.destinationPagePath, p.fragment, false);
        } else {
            Field field = (Field) copyForm.get(0).get(0);
            if(!field.getErrors().isEmpty()) {
                SessionMessages.addErrorMessage(field.getLabel() + ": " + field.getErrors().get(0));
            }
            field = (Field) copyForm.get(0).get(1);
            if(!field.getErrors().isEmpty()) {
                SessionMessages.addErrorMessage(field.getLabel() + ": " + field.getErrors().get(0));
            }
            return new RedirectResolution(dispatch.getOriginalPath());
        }
    }

    protected Resolution copyPage(String destinationPagePath, String newName, boolean deleteOriginal) {
        if(StringUtils.isEmpty(destinationPagePath)) {
            SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.copyOrMove.noDestination"));
            return new RedirectResolution(dispatch.getOriginalPath());
        }
        PageInstance pageInstance = getPageInstance();
        PageInstance oldParent = pageInstance.getParent();
        if(oldParent == null) {
            SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.copyOrMove.forbidden.root"));
            return new RedirectResolution(dispatch.getOriginalPath());
        }
        if(deleteOriginal) {
            logger.debug("Checking if we've been asked to move the landing page...");
            Dispatcher dispatcher = DispatcherUtil.get(context.getRequest());
            String contextPath = context.getRequest().getContextPath();
            String landingPagePath = portofinoConfiguration.getString(AppProperties.LANDING_PAGE);
            Dispatch landingPageDispatch = dispatcher.getDispatch(contextPath, landingPagePath);
            if(landingPageDispatch != null &&
               landingPageDispatch.getLastPageInstance().getDirectory().equals(pageInstance.getDirectory())) {
                SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.move.forbidden.landing"));
                return new RedirectResolution(dispatch.getOriginalPath());
            }
        }

        PageInstance newParent;
        if("/".equals(destinationPagePath)) {
            File dir = pagesDir;
            try {
                newParent = new PageInstance(null, dir, DispatcherLogic.getPage(dir), null);
            } catch (Exception e) {
                throw new Error("Couldn't load root page", e);
            }
        } else {
            Dispatcher dispatcher = DispatcherUtil.get(context.getRequest());
            Dispatch destinationDispatch =
                    dispatcher.getDispatch(context.getRequest().getContextPath(), destinationPagePath);
            //TODO gestione eccezioni
            newParent = destinationDispatch.getLastPageInstance();
        }
        if(newParent.getDirectory().equals(oldParent.getDirectory())) {
            List<String> params = newParent.getParameters();
            newParent = new PageInstance(newParent.getParent(), newParent.getDirectory(), oldParent.getPage(), null);
            newParent.getParameters().addAll(params);
        }

        //Check permissions on target parent page
        Subject subject = SecurityUtils.getSubject();
        if(!SecurityLogic.hasPermissions(portofinoConfiguration, newParent, subject, AccessLevel.EDIT)) {
            SessionMessages.addErrorMessage(ElementsThreadLocals.getText("page.copyOrMove.forbidden.accessLevel"));
            return new RedirectResolution(dispatch.getOriginalPath());
        }

        if(newParent != null) { //TODO vedi sopra
            newName = StringUtils.isEmpty(newName) ? pageInstance.getName() : newName;
            File newDirectory = newParent.getChildPageDirectory(newName);
            File newParentDirectory = newDirectory.getParentFile();
            logger.debug("Ensuring that new parent directory {} exists", newParentDirectory);
            ElementsFileUtils.safeMkdirs(newParentDirectory);
            if(!newDirectory.exists()) {
                try {
                    Iterator<ChildPage> it = oldParent.getLayout().getChildPages().iterator();
                    ChildPage oldChildPage = null;
                    String oldName = pageInstance.getName();
                    while (it.hasNext()) {
                        oldChildPage = it.next();
                        if(oldChildPage.getName().equals(oldName)) {
                            if(deleteOriginal) {
                                it.remove();
                            }
                            break;
                        }
                    }
                    if(deleteOriginal) {
                        logger.debug("Removing from old parent");
                        DispatcherLogic.savePage(oldParent.getDirectory(), oldParent.getPage());
                        logger.debug("Moving directory");
                        FileUtils.moveDirectory(pageInstance.getDirectory(), newDirectory);
                    } else {
                        logger.debug("Copying directory");
                        FileUtils.copyDirectory(pageInstance.getDirectory(), newDirectory);
                        logger.debug("Generating a new Id for the new page");
                        Page newPage = DispatcherLogic.getPage(newDirectory);
                        String pageId = RandomUtil.createRandomId();
                        newPage.setId(pageId);
                        DispatcherLogic.savePage(newDirectory, newPage);
                    }
                    logger.debug("Registering the new child page in parent page (directory: {})",
                            newDirectory);

                    ChildPage newChildPage = new ChildPage();
                    newChildPage.setName(newName);
                    if(oldChildPage != null) {
                        newChildPage.setShowInNavigation(oldChildPage.isShowInNavigation());
                    } else {
                        newChildPage.setShowInNavigation(true);
                    }
                    newParent.getLayout().getChildPages().add(newChildPage);
                    DispatcherLogic.savePage(newParent.getDirectory(), newParent.getPage());
                } catch (Exception e) {
                    logger.error("Couldn't copy/move page", e);
                    String msg = ElementsThreadLocals.getText("page.copyOrMove.failed", destinationPagePath);
                    SessionMessages.addErrorMessage(msg);
                }
            } else {
                String msg = ElementsThreadLocals.getText("page.copyOrMove.destinationExists", newDirectory.getAbsolutePath());
                SessionMessages.addErrorMessage(msg);
                return new RedirectResolution(dispatch.getOriginalPath());
            }
            if(newParent.getParameters().isEmpty()) {
                return new RedirectResolution(
                        destinationPagePath + (destinationPagePath.endsWith("/") ? "" : "/") + newName);
            } else {
                //Detail
                newParent.getParameters().clear();
                return new RedirectResolution(newParent.getPath());
            }
        } else {
            String msg = ElementsThreadLocals.getText("page.copyOrMove.invalidDestination", destinationPagePath);
            SessionMessages.addErrorMessage(msg);
            return new RedirectResolution(dispatch.getOriginalPath());
        }
    }

    private void prepareNewPageForm() throws Exception {
        DefaultSelectionProvider classSelectionProvider = new DefaultSelectionProvider("actionClassName");
        for(PageActionInfo info : registry) {
            classSelectionProvider.appendRow(info.actionClass.getName(), info.description, true);
        }
        Subject subject = SecurityUtils.getSubject();

        //root + at least 1 child
        boolean includeSiblingOption =
                dispatch.getPageInstancePath().length > 2 &&
                SecurityLogic.hasPermissions(
                        portofinoConfiguration, dispatch.getPageInstance(-2), subject, AccessLevel.EDIT);
        List<String[]> insertPositions = new ArrayList<String[]>();

        //TODO I18n

        //Check permissions on target parent
        File rootDirectory = pagesDir;
        Page rootPage = DispatcherLogic.getPage(rootDirectory);
        PageInstance rootPageInstance = new PageInstance(null, rootDirectory, rootPage, null);
        if(SecurityLogic.hasPermissions(portofinoConfiguration, rootPageInstance, subject, AccessLevel.EDIT)) {
            insertPositions.add(new String[] {InsertPosition.TOP.name(), "at the top level"});
        }

        //Assumiamo che l'utente abbia almeno i permessi per creare una pagina figlia, altrimenti non sarebbe qui.
        //In ogni caso, la verifica sui permessi viene ripetuta alla creazione vera e propria.
        insertPositions.add(new String[] {InsertPosition.CHILD.name(), "as a child of " + getPage().getTitle()});
        if(includeSiblingOption) {
            insertPositions.add(new String[] {InsertPosition.SIBLING.name(), "as a sibling of " + getPage().getTitle()});
        }
        DefaultSelectionProvider insertPositionSelectionProvider = new DefaultSelectionProvider("insertPositionName");
        for(String[] posAndLabel : insertPositions) {
            insertPositionSelectionProvider.appendRow(posAndLabel[0], posAndLabel[1], true);
        }
        newPageForm = new FormBuilder(NewPage.class)
                .configFields(NEW_PAGE_SETUP_FIELDS)
                .configFieldSetNames("Page setup")
                .configSelectionProvider(classSelectionProvider, "actionClassName")
                .configSelectionProvider(insertPositionSelectionProvider, "insertPositionName")
                .build();
        ((SelectField) newPageForm.findFieldByPropertyName("insertPositionName")).setValue(InsertPosition.CHILD.name());
        newPageForm.readFromRequest(context.getRequest());
    }

    //--------------------------------------------------------------------------
    // Page children
    //--------------------------------------------------------------------------

    protected List<EditChildPage> childPages = new ArrayList<EditChildPage>();
    protected List<EditChildPage> detailChildPages = new ArrayList<EditChildPage>();
    protected TableForm childPagesForm;
    protected TableForm detailChildPagesForm;

    public Resolution pageChildren() {
        setupChildPages();
        return forwardToPageChildren();
    }

    protected void setupChildPages() {
        File directory = getPageInstance().getDirectory();
        childPagesForm = setupChildPagesForm(childPages, directory, getPage().getLayout(), "");
        if(PageActionLogic.supportsDetail(getPageInstance().getActionClass())) {
            File detailDirectory = new File(directory, PageInstance.DETAIL);
            if(!detailDirectory.isDirectory() && !ElementsFileUtils.safeMkdir(detailDirectory)) {
                logger.error("Could not create detail directory{}", detailDirectory.getAbsolutePath());
                SessionMessages.addErrorMessage("Could not create detail directory");
                return;
            }
            detailChildPagesForm =
                    setupChildPagesForm(detailChildPages, detailDirectory, getPage().getDetailLayout(), "detail");
        }
    }

    protected TableForm setupChildPagesForm
            (List<EditChildPage> childPages, File childrenDirectory, Layout layout, String prefix) {
        TableForm childPagesForm;
        FileFilter filter = new FileFilter() {
            public boolean accept(File pathname) {
                return pathname.isDirectory();
            }
        };
        List<EditChildPage> unorderedChildPages = new ArrayList<EditChildPage>();
        for (File dir : childrenDirectory.listFiles(filter)) {
            if(PageInstance.DETAIL.equals(dir.getName())) {
                continue;
            }
            EditChildPage childPage = null;
            String title;
            try {
                Page page = DispatcherLogic.getPage(dir);
                title = page.getTitle();
            } catch (Exception e) {
                logger.error("Couldn't load page for " + dir, e);
                title = null;
            }
            for(ChildPage cp : layout.getChildPages()) {
                if(cp.getName().equals(dir.getName())) {
                    childPage = new EditChildPage();
                    childPage.active = true;
                    childPage.name = cp.getName();
                    childPage.showInNavigation = cp.isShowInNavigation();
                    childPage.title = title;
                    childPage.embedded = cp.getContainer() != null;
                    break;
                }
            }
            if(childPage == null) {
                childPage = new EditChildPage();
                childPage.active = false;
                childPage.name = dir.getName();
                childPage.title = title;
            }
            unorderedChildPages.add(childPage);
        }

        logger.debug("Adding known pages in order");
        for(ChildPage cp : layout.getChildPages()) {
            for(EditChildPage ecp : unorderedChildPages) {
                if(cp.getName().equals(ecp.name)) {
                    childPages.add(ecp);
                    break;
                }
            }
        }
        logger.debug("Adding unknown pages");
        for(EditChildPage ecp : unorderedChildPages) {
            if(!ecp.active) {
                childPages.add(ecp);
            }
        }

        childPagesForm = new TableFormBuilder(EditChildPage.class)
                .configNRows(childPages.size())
                .configFields(getChildPagesFormFields())
                .configPrefix(prefix)
                .build();
        childPagesForm.readFromObject(childPages);
        return childPagesForm;
    }

    protected String[] getChildPagesFormFields() {
        return new String[] { "active", "name", "title", "showInNavigation", "embedded" };
    }

    @Button(list = "page-children-edit", key = "commons.update", order = 1, type = Button.TYPE_PRIMARY)
    @RequiresPermissions(level = AccessLevel.EDIT)
    public Resolution updatePageChildren() {
        setupChildPages();
        String[] order = context.getRequest().getParameterValues("directChildren");
        if(order == null) {
            order = new String[0];
        }
        boolean success = updatePageChildren(childPagesForm, childPages, getPage().getLayout(), order);
        childPages.clear();
        if(success && detailChildPagesForm != null) {
            order = context.getRequest().getParameterValues("detailChildren");
            if(order == null) {
                order = new String[0];
            }
            updatePageChildren(detailChildPagesForm, detailChildPages, getPage().getDetailLayout(), order);
            detailChildPages.clear();
        }
        setupChildPages(); //Re-read sorted values
        SessionMessages.addInfoMessage(ElementsThreadLocals.getText("commons.update.successful"));
        return forwardToPageChildren();
    }

    protected boolean updatePageChildren
            (TableForm childPagesForm, List<EditChildPage> childPages, Layout layout, String[] order) {
        childPagesForm.readFromRequest(context.getRequest());
        if(!childPagesForm.validate()) {
            return false;
        }
        childPagesForm.writeToObject(childPages);
        List<ChildPage> newChildren = new ArrayList<ChildPage>();
        for(EditChildPage editChildPage : childPages) {
            if(!editChildPage.active) {
                continue;
            }
            ChildPage childPage = null;
            for(ChildPage cp : layout.getChildPages()) {
                if(cp.getName().equals(editChildPage.name)) {
                    childPage = cp;
                    break;
                }
            }
            if(childPage == null) {
                childPage = new ChildPage();
                childPage.setName(editChildPage.name);
            }
            childPage.setShowInNavigation(editChildPage.showInNavigation);
            if(editChildPage.embedded) {
                if(childPage.getContainer() == null) {
                    childPage.setContainer(AbstractPageAction.DEFAULT_LAYOUT_CONTAINER);
                    childPage.setOrder("0");
                }
            } else {
                childPage.setContainer(null);
                childPage.setOrder(null);
            }
            newChildren.add(childPage);
            if(!editChildPage.showInNavigation && !editChildPage.embedded) {
                String msg = ElementsThreadLocals.getText("page.warnNotShowInNavigationNotEmbedded", editChildPage.name);
                SessionMessages.addWarningMessage(msg);
            }
        }
        List<ChildPage> sortedChildren = new ArrayList<ChildPage>();
        for(String name : order) {
            for(ChildPage p : newChildren) {
                if(name.equals(p.getName())) {
                    sortedChildren.add(p);
                    break;
                }
            }
        }
        layout.getChildPages().clear();
        layout.getChildPages().addAll(sortedChildren);
        try {
            DispatcherLogic.savePage(getPageInstance());
        } catch (Exception e) {
            logger.error("Couldn't save page", e);
            String msg = ElementsThreadLocals.getText("page.update.failed", e.getMessage());
            SessionMessages.addErrorMessage(msg);
            return false;
        }
        return true;
    }

    public List<EditChildPage> getChildPages() {
        return childPages;
    }

    public TableForm getChildPagesForm() {
        return childPagesForm;
    }

    public List<EditChildPage> getDetailChildPages() {
        return detailChildPages;
    }

    public TableForm getDetailChildPagesForm() {
        return detailChildPagesForm;
    }

    public String getPageTemplate() {
        return "/templates/default";
    }

    protected Resolution forwardToPageChildren() {
        return new ForwardResolution("/layouts/page/children.jsp");
    }

    //--------------------------------------------------------------------------
    // Page permisssions
    //--------------------------------------------------------------------------

    protected Set<String> groups;

    //<group, level>
    protected Map<String, String> accessLevels = new HashMap<String, String>();
    //<group, permissions>
    protected Map<String, List<String>> permissions = new HashMap<String, List<String>>();

    protected String testUserId;
    protected Map<Serializable, String> users;
    protected AccessLevel testedAccessLevel;
    protected Set<String> testedPermissions;

    @RequiresPermissions(level = AccessLevel.DEVELOP) //Altrimenti un utente può cambiare i propri permessi
    public Resolution pagePermissions() {
        setupGroups();

        PortofinoRealm portofinoRealm = ShiroUtils.getPortofinoRealm();
        users = new LinkedHashMap<Serializable, String>();
        users.put(null, "(anonymous)");
        users.putAll(portofinoRealm.getUsers());

        return forwardToPagePermissions();
    }

    @Button(list = "testUserPermissions", key = "user.permissions.test")
    @RequiresPermissions(level = AccessLevel.DEVELOP)
    public Resolution testUserPermissions() {
        testUserId = StringUtils.defaultIfEmpty(testUserId, null);
        PortofinoRealm portofinoRealm = ShiroUtils.getPortofinoRealm();
        PrincipalCollection principalCollection;
        if(!StringUtils.isEmpty(testUserId)) {
            Serializable user = portofinoRealm.getUserById(testUserId);
            principalCollection = new SimplePrincipalCollection(user, "realm");
        } else {
            principalCollection = null;
        }
        Permissions permissions = SecurityLogic.calculateActualPermissions(getPageInstance());
        testedAccessLevel = AccessLevel.NONE;
        testedPermissions = new HashSet<String>();

        SecurityManager securityManager = SecurityUtils.getSecurityManager();

        for(AccessLevel level : AccessLevel.values()) {
            if(level.isGreaterThanOrEqual(testedAccessLevel) &&
                SecurityLogic.hasPermissions(portofinoConfiguration, permissions, securityManager, principalCollection, level)) {
                testedAccessLevel = level;
            }
        }
        String[] supportedPermissions = getSupportedPermissions();
        if(supportedPermissions != null) {
            for(String permission : supportedPermissions) {
                boolean permitted =
                        SecurityLogic.hasPermissions
                                (portofinoConfiguration, permissions, securityManager,
                                 principalCollection, testedAccessLevel, permission);
                if(permitted) {
                    testedPermissions.add(permission);
                }
            }
        }

        return pagePermissions();
    }

    public String[] getSupportedPermissions() {
        Class<?> actualActionClass = getPageInstance().getActionClass();
        SupportsPermissions supportsPermissions =
                actualActionClass.getAnnotation(SupportsPermissions.class);
        while(supportsPermissions == null && actualActionClass.getSuperclass() != Object.class) {
            actualActionClass = actualActionClass.getSuperclass();
            supportsPermissions = actualActionClass.getAnnotation(SupportsPermissions.class);
        }
        if(supportsPermissions != null && supportsPermissions.value().length > 0) {
            return supportsPermissions.value();
        } else {
            return null;
        }
    }

    protected Resolution forwardToPagePermissions() {
        return new ForwardResolution("/layouts/page/permissions.jsp");
    }

    protected void setupGroups() {
        PortofinoRealm portofinoRealm = ShiroUtils.getPortofinoRealm();
        groups = portofinoRealm.getGroups();
    }

    @Button(list = "page-permissions-edit", key = "commons.update", order = 1, type = Button.TYPE_PRIMARY)
    @RequiresPermissions(level = AccessLevel.DEVELOP)
    public Resolution updatePagePermissions() {
        try {
            updatePagePermissions(getPageInstance());
            SessionMessages.addInfoMessage(ElementsThreadLocals.getText("permissions.page.updated"));
            return new RedirectResolution(HttpUtil.getRequestedPath(context.getRequest()))
                    .addParameter("pagePermissions").addParameter("originalPath", dispatch.getOriginalPath());
        } catch (Exception e) {
            logger.error("Couldn't update page permissions", e);
            SessionMessages.addInfoMessage(ElementsThreadLocals.getText("permissions.page.notUpdated"));
            return new RedirectResolution(HttpUtil.getRequestedPath(context.getRequest()))
                    .addParameter("pagePermissions").addParameter("originalPath", dispatch.getOriginalPath());
        }
    }

    protected void updatePagePermissions(PageInstance page) throws Exception {
        Permissions pagePermissions = page.getPage().getPermissions();

        pagePermissions.getGroups().clear();

        Map<String, Group> groups = new HashMap<String, Group>();

        for(Map.Entry<String, String> entry : accessLevels.entrySet()) {
            Group group = new Group();
            group.setName(entry.getKey());
            group.setAccessLevel(entry.getValue());
            groups.put(group.getName(), group);
        }

        for(Map.Entry<String, List<String>> custPerm : permissions.entrySet()) {
            String groupId = custPerm.getKey();
            Group group = groups.get(groupId);
            if(group == null) {
                group = new Group();
                group.setName(groupId);
                groups.put(groupId, group);
            }
            group.getPermissions().addAll(custPerm.getValue());
        }

        for(Group group : groups.values()) {
            pagePermissions.getGroups().add(group);
        }

        DispatcherLogic.savePage(page);
    }

    public AccessLevel getLocalAccessLevel(Page currentPage, String groupId) {
        Permissions currentPagePermissions = currentPage.getPermissions();
        for(Group group : currentPagePermissions.getGroups()) {
            if(groupId.equals(group.getName())) {
                return group.getActualAccessLevel();
            }
        }
        return null;
    }

    //--------------------------------------------------------------------------
    // Dialog
    //--------------------------------------------------------------------------

    protected Form moveForm;
    protected Form copyForm;

    public Resolution confirmDelete() {
        return new ForwardResolution("/layouts/page-crud/deletePageDialog.jsp");
    }

    public Resolution chooseNewLocation() {
        buildMovePageForm();
        return new ForwardResolution("/layouts/page-crud/movePageDialog.jsp");
    }

    public Resolution copyPageDialog() {
        buildCopyPageForm();
        return new ForwardResolution("/layouts/page-crud/copyPageDialog.jsp");
    }

    protected void buildMovePageForm() {
        PageInstance pageInstance = dispatch.getLastPageInstance();
        SelectionProvider pagesSelectionProvider =
                DispatcherLogic.createPagesSelectionProvider
                        (pagesDir, true, true, pageInstance.getDirectory());
        moveForm = new FormBuilder(MovePage.class)
                .configReflectiveFields()
                .configSelectionProvider(pagesSelectionProvider, "destinationPagePath")
                .build();
    }

    protected void buildCopyPageForm() {
        PageInstance pageInstance = dispatch.getLastPageInstance();
        SelectionProvider pagesSelectionProvider =
                DispatcherLogic.createPagesSelectionProvider
                        (pagesDir, true, true, pageInstance.getDirectory());
        copyForm = new FormBuilder(CopyPage.class)
                .configReflectiveFields()
                .configSelectionProvider(pagesSelectionProvider, "destinationPagePath")
                .build();
    }

    public Form getMoveForm() {
        return moveForm;
    }

    public Form getCopyForm() {
        return copyForm;
    }

    //--------------------------------------------------------------------------
    // Getters/Setters
    //--------------------------------------------------------------------------

    public Set<String> getGroups() {
        return groups;
    }

    public Map<String, String> getAccessLevels() {
        return accessLevels;
    }

    public void setAccessLevels(Map<String, String> accessLevels) {
        this.accessLevels = accessLevels;
    }

    public Map<String, List<String>> getPermissions() {
        return permissions;
    }

    public void setPermissions(Map<String, List<String>> permissions) {
        this.permissions = permissions;
    }

    public String getTestUserId() {
        return testUserId;
    }

    public void setTestUserId(String testUserId) {
        this.testUserId = testUserId;
    }

    public Map<Serializable, String> getUsers() {
        return users;
    }

    public AccessLevel getTestedAccessLevel() {
        return testedAccessLevel;
    }

    public Set<String> getTestedPermissions() {
        return testedPermissions;
    }

    public Form getNewPageForm() {
        return newPageForm;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public String getCancelReturnUrl() {
        return dispatch.getAbsoluteOriginalPath();
    }

}
