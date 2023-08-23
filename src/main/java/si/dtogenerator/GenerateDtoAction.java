package si.dtogenerator;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.lang.ImportOptimizer;
import com.intellij.lang.java.JavaImportOptimizer;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.openapi.project.Project;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.Objects;

public class GenerateDtoAction extends AnAction {

    @Override
    public void update(AnActionEvent e) {
        PsiClass selectedClass = getSelectedClass(e);
        // Only enable the action if the selected class is a JPA Entity
        e.getPresentation().setEnabledAndVisible(isJpaEntity(selectedClass));
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        PsiClass selectedClass = getSelectedClass(e);
        if (selectedClass != null && isJpaEntity(selectedClass)) {
            generateNewDtoForEntity(selectedClass);
            generateReturnDtoForEntity(selectedClass);
            generatePutDtoForEntity(selectedClass);
            generateUpdateDtoForEntity(selectedClass);
            generateBP(selectedClass);
            generateDAO(selectedClass);
        }
    }

    private PsiClass getSelectedClass(AnActionEvent e) {
        // Obtain the currently selected element from the Project View or Editor
        PsiElement selectedElement = e.getData(CommonDataKeys.PSI_ELEMENT);

        if (selectedElement == null) {
            System.out.println("No PSI element selected.");
            return null;
        }

        System.out.println("Selected PSI element: " + selectedElement);

        // If the selected element is already a PsiClass, cast and return it
        if (selectedElement instanceof PsiClass) {
            return (PsiClass) selectedElement;
        }

        // Otherwise, try to find the closest parent of this element that is a PsiClass
        PsiClass selectedClass = PsiTreeUtil.getParentOfType(selectedElement, PsiClass.class);

        if (selectedClass == null) {
            System.out.println("No PsiClass found for the selected element.");
        }

        return selectedClass;
    }

    private boolean isJpaEntity(PsiClass psiClass) {
        if (psiClass == null) {
            System.out.println("psiClass is null");
            return false;
        }
        PsiAnnotation entityAnnotation = psiClass.getAnnotation("jakarta.persistence.Entity");
        if (entityAnnotation == null) {
            entityAnnotation = psiClass.getAnnotation("javax.persistence.Entity");
        }

        System.out.println("Is class JPA entity: " + entityAnnotation);
        return entityAnnotation != null;
    }

    private void generateNewDtoForEntity(PsiClass entityClass) {
        generateDTO(entityClass, "New" + entityClass.getName() + "DTO", false, false);
    }

    private void generateUpdateDtoForEntity(PsiClass entityClass) {
        generateDTO(entityClass, entityClass.getName() + "UpdateDTO", false, true);
    }

    private void generatePutDtoForEntity(PsiClass entityClass) {
        generateDTO(entityClass, entityClass.getName() + "PutDTO", false, false);
    }

    private void generateReturnDtoForEntity(PsiClass entityClass) {
        generateDTO(entityClass, entityClass.getName() + "ReturnDTO", true, false);
    }

    private void generateDTO(PsiClass entityClass, String className, boolean isReturn, boolean isUpdate) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();

        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        //Get the service.classname directory
        PsiDirectory classDirectory = getServiceClassDirectory(entityClass, directory);

        // Check if DTO class already exists
        PsiFile existingFile = classDirectory.findFile(className + ".java");
        if (existingFile != null) {
            System.out.println(className + " already exists");
            return; // Exit the method if DTO class already exists
        }

        // Create DTO class
        PsiClass DTOClass = factory.createClass(className);

        // Create a default constructor for DTO class
        PsiMethod defaultConstructor = factory.createConstructor();
        DTOClass.add(defaultConstructor);

        // Create a StringBuilder to hold the constructor parameters and body
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();

        for (PsiField field : entityClass.getFields()) {
            if (hasJpaAnnotations(field)) {
                PsiType fieldType;
                String fieldName;
                boolean isNotNull = false;

                if (!isUpdate) {
                    PsiAnnotation columnAnnotation = field.getAnnotation("jakarta.persistence.Column");
                    if (columnAnnotation == null) {
                        columnAnnotation = field.getAnnotation("javax.persistence.Column");
                    }
                    if (columnAnnotation != null) {
                        PsiAnnotationMemberValue nullableValue = columnAnnotation.findAttributeValue("nullable");
                        if (nullableValue != null && "false".equals(nullableValue.getText())) {
                            isNotNull = true;
                        }
                    }
                }

                if (isForeignKey(field)) {
                    if (isReturn) {
                        fieldType = factory.createTypeByFQClassName(field.getType().getPresentableText() + "ReturnDTO", entityClass.getResolveScope());
                        fieldName = field.getName();
                    } else {
                        fieldType = factory.createTypeByFQClassName("java.lang.String", entityClass.getResolveScope());
                        fieldName = field.getName() + "Id";
                    }
                } else {
                    fieldType = field.getType();
                    fieldName = field.getName();
                }

                // Add field to DTO class with annotations
                PsiField newField = factory.createField(fieldName, fieldType);

                if (isNotNull) {
                    if (fieldType.getCanonicalText().equals("java.lang.String")) {
                        newField.getModifierList().addAnnotation("NotBlank");
                    } else {
                        newField.getModifierList().addAnnotation("NotNull");
                    }
                }
                DTOClass.add(newField);

                // Add parameter to constructor
                if (constructorParams.length() > 0) {
                    constructorParams.append(", ");
                }
                constructorParams.append(fieldType.getPresentableText()).append(" ").append(fieldName);

                // Add assignment to constructor body
                constructorBody.append("this.").append(fieldName).append(" = ").append(fieldName).append("; ");


                // Add getter for the field
                PsiMethod getter = factory.createMethodFromText(
                        "public " + fieldType.getPresentableText() + " get" +
                                Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "() { return " + fieldName + "; }", DTOClass);
                DTOClass.add(getter);

                // Add setter for the field
                PsiMethod setter = factory.createMethodFromText(
                        "public void set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "(" + fieldType.getPresentableText() + " " + fieldName + ") { this." +
                                fieldName + " = " + fieldName + "; }", DTOClass);
                DTOClass.add(setter);
            }
        }

        // Create and add constructor with parameters to DTO class
        PsiMethod constructor = factory.createMethodFromText(
                "public " + DTOClass.getName() + "(" + constructorParams.toString() + ") { " +
                        constructorBody.toString() +
                        " }", DTOClass);
        DTOClass.add(constructor);

        // Import the annotations
        PsiJavaFile javaFile = (PsiJavaFile) DTOClass.getContainingFile();
        Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("jakarta.validation.constraints"));
        Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("javax.validation.constraints"));

        JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(javaFile.getProject());
        styleManager.optimizeImports(javaFile);

        // Use WriteCommandAction to make modifications
        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Add ReturnDTO class to the same directory as the entity
            classDirectory.add(DTOClass);
        });
        // Create mapper directory and MapStruct mapper
        createMapperForEntity(entityClass, DTOClass);
    }

    private void generateBP(PsiClass entityClass) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();

        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        //Get the beanParams directory
        PsiDirectory classDirectory = getClassDirectory(entityClass, directory, "beanParams");

        String className = entityClass.getName() + "BP";

        // Check if BP class already exists
        PsiFile existingFile = classDirectory.findFile(className + ".java");
        if (existingFile != null) {
            System.out.println(className + " already exists");
            return; // Exit the method if DTO class already exists
        }

        // Create BP class
        PsiClass BPClass = factory.createClass(className);

        // Create a default constructor for BP class
        PsiMethod defaultConstructor = factory.createConstructor();
        BPClass.add(defaultConstructor);

        // Create a StringBuilder to hold the constructor parameters and body
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();

        for (PsiField field : entityClass.getFields()) {
            if (hasJpaAnnotations(field)) {
                PsiType fieldType;
                String fieldName;

                if (isForeignKey(field)) {
                    fieldType = factory.createTypeByFQClassName("java.lang.String", entityClass.getResolveScope());
                    fieldName = field.getName() + "Id";

                } else {
                    fieldType = field.getType();
                    fieldName = field.getName();
                }

                // Add field to BP class with annotations
                PsiField newField = factory.createField(fieldName, fieldType);

                newField.getModifierList().addAnnotation("QueryParam(\"" + fieldName + "\")");

                BPClass.add(newField);

                // Add parameter to constructor
                if (constructorParams.length() > 0) {
                    constructorParams.append(", ");
                }
                constructorParams.append(fieldType.getPresentableText()).append(" ").append(fieldName);

                // Add assignment to constructor body
                constructorBody.append("this.").append(fieldName).append(" = ").append(fieldName).append("; ");


                // Add getter for the field
                PsiMethod getter = factory.createMethodFromText(
                        "public " + fieldType.getPresentableText() + " get" +
                                Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "() { return " + fieldName + "; }", BPClass);
                BPClass.add(getter);

                // Add setter for the field
                PsiMethod setter = factory.createMethodFromText(
                        "public void set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "(" + fieldType.getPresentableText() + " " + fieldName + ") { this." +
                                fieldName + " = " + fieldName + "; }", BPClass);
                BPClass.add(setter);
            }
        }

        // Create and add constructor with parameters to BP class
        PsiMethod constructor = factory.createMethodFromText(
                "public " + BPClass.getName() + "(" + constructorParams.toString() + ") { " +
                        constructorBody.toString() +
                        " }", BPClass);
        BPClass.add(constructor);

        // Import the annotations
        PsiJavaFile javaFile = (PsiJavaFile) BPClass.getContainingFile();
        Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("jakarta.ws.rs"));
        Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("javax.ws.rs"));

        JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(javaFile.getProject());
        styleManager.optimizeImports(javaFile);

        // Use WriteCommandAction to make modifications
        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Add BP class to the same directory as the entity
            classDirectory.add(BPClass);
        });
    }

    private void generateDAO(PsiClass entityClass) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();

        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        //Get the DAO directory
        PsiDirectory classDirectory = getClassDirectory(entityClass, directory, "DAO");

        String className = entityClass.getName() + "DAO";

        // Check if DAO class already exists
        PsiFile existingFile = classDirectory.findFile(className + ".java");
        if (existingFile != null) {
            System.out.println(className + " already exists");
            return; // Exit the method if DTO class already exists
        }

        StringBuilder sb = new StringBuilder();

        sb.append("\n\n@RequestScoped\n");
        sb.append("public class ").append(className).append(" extends PetrolGenericDAO<")
                .append(entityClass.getName()).append("> {\n\n");

        // Generate listAll method
        generateListMethod(sb, entityClass, "List<" + entityClass.getName() + ">", "getResultList", "listAll");

        // Generate listCountAll method
        generateListMethod(sb, entityClass, "Long", "getSingleResult", "listAllCount");

        // Close class
        sb.append("}\n");

        // Use WriteCommandAction to make modifications
        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            PsiFileFactory fileFactory = PsiFileFactory.getInstance(entityClass.getProject());
            PsiFile DAOFile = fileFactory.createFileFromText(className + ".java", JavaFileType.INSTANCE, sb);

            // Import the annotations
            PsiJavaFile javaFile = (PsiJavaFile) DAOFile.getContainingFile();
            if (isQuarkus3Project(entityClass.getProject())) {
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("jakarta.persistence.criteria"));
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("jakarta.persistence"));
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("jakarta.enterprise.context"));
            }
            else {
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("javax.persistence.criteria"));
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("javax.persistence"));
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("javax.enterprise.context"));
            }
            Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("si.petrol.beanParams"));
            Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("si.petrol.entity"));
            Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("si.petrol.entity.notes"));
            Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("java.util"));

            JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(DAOFile.getProject());
            styleManager.optimizeImports(DAOFile);

            classDirectory.add(DAOFile);

        });


    }

    private void generateListMethod(StringBuilder sb, PsiClass entityClass, String returnType, String queryMethod, String methodName) {
        sb.append("    public ").append(returnType).append(" ").append(methodName).append("(")
                .append(entityClass.getName()).append("BP ").append(lowercaseFirstLetter(entityClass.getName())).append("BP");

        String queryClass;
        String[] queryClasses = returnType.split("<");
        if (queryClasses.length > 1) {
            queryClass = queryClasses[1].substring(0, queryClasses[1].length() - 1);
        } else {
            queryClass = returnType;
        }

        // Add FK parameters from the entity
        for (PsiField field : entityClass.getFields()) {
            if (hasJpaAnnotations(field)) {
                if (isForeignKey(field)) {
                    sb.append(", ").append(field.getType().getPresentableText()).append(" ").append(field.getName());
                }
            }
        }

        sb.append(") {\n");
        sb.append("        CriteriaBuilder cb = getEntityManager().getCriteriaBuilder();\n");
        sb.append("        CriteriaQuery<").append(queryClass).append("> cq = cb.createQuery(")
                .append(queryClass).append(".class);\n");
        sb.append("        Root<").append(entityClass.getName()).append("> root = cq.from(")
                .append(entityClass.getName()).append(".class);\n\n");

        // Add predicates for FKs
        sb.append("        List<Predicate> predicates = new ArrayList<>();\n");
        for (PsiField field : entityClass.getFields()) {
            if (hasJpaAnnotations(field)) {

                if (isForeignKey(field)) {
                    sb.append("        if (Objects.nonNull(").append(field.getName()).append(")) {\n");
                    sb.append("            predicates.add(cb.equal(root.get(").append(entityClass.getName())
                            .append("_.").append(separateByUpperCaseAndAddUnderline(field.getName()).toUpperCase()).append("), ")
                            .append(field.getName()).append("));\n");
                    sb.append("        }\n");

                } else {
                    sb.append("        if (Objects.nonNull(").append(lowercaseFirstLetter(entityClass.getName())).append("BP.get").append(uppercaseFirstLetter(field.getName())).append("())) {\n");
                    sb.append("            predicates.add(cb.equal(root.get(").append(entityClass.getName())
                            .append("_.").append(separateByUpperCaseAndAddUnderline(field.getName()).toUpperCase()).append("), ")
                            .append(lowercaseFirstLetter(entityClass.getName())).append("BP.get").append(uppercaseFirstLetter(field.getName())).append("()));\n");
                    sb.append("        }\n");
                }
            }
        }

        sb.append("\n        cq.where(predicates.toArray(new Predicate[0]));\n");
        sb.append("        TypedQuery<").append(queryClass).append("> query = getEntityManager().createQuery(cq);\n\n");
        sb.append("        return query.").append(queryMethod).append("();\n");
        sb.append("    }\n");
    }


    private PsiDirectory getServiceClassDirectory(PsiClass entityClass, PsiDirectory directory) {
        // Get the parent directory
        PsiDirectory parentDirectory = directory.getParent();

        // Declare classDirectory outside the lambda
        final PsiDirectory[] classDirectory = new PsiDirectory[1];

        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Check if the parent directory has a subdirectory named service.classname
            if (parentDirectory != null) {
                PsiDirectory serviceDirectory = parentDirectory.findSubdirectory("service");
                if (serviceDirectory == null) {
                    serviceDirectory = parentDirectory.createSubdirectory("service");
                }
                classDirectory[0] = serviceDirectory.findSubdirectory(Objects.requireNonNull(lowercaseFirstLetter(entityClass.getName())));
                if (classDirectory[0] == null) {
                    classDirectory[0] = serviceDirectory.createSubdirectory(Objects.requireNonNull(lowercaseFirstLetter(entityClass.getName())));
                }
            }
        });

        return classDirectory[0];
    }

    private PsiDirectory getClassDirectory(PsiClass entityClass, PsiDirectory directory, String subdirectoryName) {
        // Get the parent directory
        PsiDirectory parentDirectory = directory.getParent();

        // Declare classDirectory outside the lambda
        final PsiDirectory[] classDirectory = new PsiDirectory[1];

        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Check if the parent directory has a subdirectory named service.classname
            if (parentDirectory != null) {
                PsiDirectory serviceDirectory = parentDirectory.findSubdirectory(subdirectoryName);
                if (serviceDirectory == null) {
                    serviceDirectory = parentDirectory.createSubdirectory(subdirectoryName);
                }
                classDirectory[0] = serviceDirectory;
            }
        });

        return classDirectory[0];
    }

    private String lowercaseFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str; // return the original string if it's null or empty
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    private String uppercaseFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str; // return the original string if it's null or empty
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private String separateByUpperCaseAndAddUnderline(String input) {
        String result = "";
        int start = 0;
        for (int i = 1; i < input.length(); i++) {
            if (Character.isUpperCase(input.charAt(i))) {
                result += input.substring(start, i);
                result += "_";
                start = i;
            }
        }
        result += (input.substring(start));

        return result;
    }


    private void createMapperForEntity(PsiClass entityClass, PsiClass dtoClass) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();
        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Get the parent directory
            PsiDirectory parentDirectory = directory.getParent();

            // Check if the parent directory has a subdirectory named "mapper"
            PsiDirectory mapperDirectory = null;
            if (parentDirectory != null) {
                mapperDirectory = parentDirectory.findSubdirectory("mapping");
                if (mapperDirectory == null) {
                    mapperDirectory = parentDirectory.createSubdirectory("mapping");
                }
            }

            String mapperName = entityClass.getName() + "Mapper";
            PsiFile existingMapper = mapperDirectory.findFile(mapperName + ".java");
            String mappingMethods = "    " + entityClass.getName() + " from" + dtoClass.getName() + "(" + dtoClass.getName() + " " + lowercaseFirstLetter(dtoClass.getName()) + ");\n" +
                    "    List<" + entityClass.getName() + ">" + " from" + dtoClass.getName() + "(List<" + dtoClass.getName() + "> " + lowercaseFirstLetter(dtoClass.getName()) + ");";

            boolean isQuarkus3 = isQuarkus3Project(entityClass.getProject());
            String componentModel = isQuarkus3 ? "jakarta" : "cdi";

            if (existingMapper == null) {
                String mapperText = "@Mapper(componentModel = \"" + componentModel + "\")\n" +
                        "public interface " + mapperName + " {\n" +
                        mappingMethods +
                        "}";

                PsiFileFactory fileFactory = PsiFileFactory.getInstance(entityClass.getProject());
                PsiFile mapperFile = fileFactory.createFileFromText(mapperName + ".java", JavaFileType.INSTANCE, mapperText);

                // Import the annotations
                PsiJavaFile javaFile = (PsiJavaFile) mapperFile.getContainingFile();
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("si.petrol.service"));
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("si.petrol.entity"));
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("java.util"));
                Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("org.mapstruct"));

                JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(mapperFile.getProject());
                styleManager.optimizeImports(mapperFile);

                mapperDirectory.add(mapperFile);

            } else {
                // If the mapper file already exists, append the new mapping methods to it
                PsiClass existingMapperClass = ((PsiJavaFile) existingMapper).getClasses()[0];
                if (existingMapperClass != null) {
                    PsiMethod method1 = null;
                    PsiMethod method2 = null;

                    if (Objects.requireNonNull(dtoClass.getName()).contains("UpdateDTO")) {
                        method1 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)"
                                + entityClass.getName() + " from" + dtoClass.getName() + "(@MappingTarget " + entityClass.getName() + " " + lowercaseFirstLetter(entityClass.getName()) + ", " + dtoClass.getName() + " " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                        method2 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)" +
                                "List<" + entityClass.getName() + ">" + " to" + dtoClass.getName() + "(@MappingTarget  List<" + entityClass.getName() + "> " + lowercaseFirstLetter(entityClass.getName()) + ", List<" + dtoClass.getName() + "> " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                    } else if (Objects.requireNonNull(dtoClass.getName()).contains("ReturnDTO")) {
                        method1 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText(dtoClass.getName() + " to" + dtoClass.getName() + "(" + entityClass.getName() + " " + lowercaseFirstLetter(entityClass.getName()) + ");", existingMapperClass);
                        method2 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("List<" + dtoClass.getName() + ">" + " to" + dtoClass.getName() + "(List<" + entityClass.getName() + "> " + lowercaseFirstLetter(entityClass.getName()) + ");", existingMapperClass);
                    } else if (Objects.requireNonNull(dtoClass.getName()).contains("PutDTO")) {
                        method1 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText(entityClass.getName() + " from" + dtoClass.getName() + "(@MappingTarget " + entityClass.getName() + " " + lowercaseFirstLetter(entityClass.getName()) + ", " + dtoClass.getName() + " " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                        method2 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("List<" + entityClass.getName() + ">" + " to" + dtoClass.getName() + "(@MappingTarget  List<" + entityClass.getName() + "> " + lowercaseFirstLetter(entityClass.getName()) + ", List<" + dtoClass.getName() + "> " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                    } else {
                        method1 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText(entityClass.getName() + " from" + dtoClass.getName() + "(" + dtoClass.getName() + " " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                        method2 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("List<" + entityClass.getName() + ">" + " from" + dtoClass.getName() + "(List<" + dtoClass.getName() + "> " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                    }

                    existingMapperClass.add(method1);
                    existingMapperClass.add(method2);

                    // Import the annotations
                    PsiJavaFile javaFile = (PsiJavaFile) existingMapperClass.getContainingFile();
                    Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("si.petrol.service." + Objects.requireNonNull(entityClass.getName()).toLowerCase()));
                    Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("si.petrol.entity"));
                    Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("java.util"));
                    Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("org.mapstruct"));

                    JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(existingMapperClass.getProject());
                    styleManager.optimizeImports(existingMapperClass.getContainingFile());

                    // Commit the changes
                    PsiDocumentManager documentManager = PsiDocumentManager.getInstance(existingMapperClass.getProject());
                    PsiFile psiFile = existingMapperClass.getContainingFile();
                    Document document = documentManager.getDocument(psiFile);
                    if (document != null) {
                        documentManager.commitDocument(document);
                    }
                }
            }
        });
    }

    private boolean isQuarkus3Project(Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir != null) {
            VirtualFile pomFile = baseDir.findChild("pom.xml");
            if (pomFile != null) {
                try {
                    String pomContent = VfsUtilCore.loadText(pomFile);
                    Pattern pattern = Pattern.compile("<quarkus\\.platform\\.version>(.*?)</quarkus\\.platform\\.version>");
                    Matcher matcher = pattern.matcher(pomContent);
                    if (matcher.find()) {
                        String quarkusVersion = matcher.group(1);
                        return quarkusVersion.startsWith("3.");
                    }
                } catch (Exception e) {
                    // Handle any exceptions that might occur while reading the pom.xml
                    e.printStackTrace();
                }
            }
        }
        return false;
    }


    // Helper method to determine if a field represents a foreign key
    private boolean isForeignKey(PsiField field) {
        // This is a basic check based on JPA annotations. Adjust as needed for your project.
        return Arrays.stream(field.getAnnotations())
                .anyMatch(annotation -> annotation.getQualifiedName().equals("jakarta.persistence.ManyToOne") ||
                        annotation.getQualifiedName().equals("jakarta.persistence.OneToOne") ||
                        annotation.getQualifiedName().equals("javax.persistence.ManyToOne") ||
                        annotation.getQualifiedName().equals("javax.persistence.OneToOne"));
    }


    private boolean hasJpaAnnotations(PsiField field) {
        String[] jpaAnnotations = {"jakarta.persistence.Id", "jakarta.persistence.Column",
                "jakarta.persistence.ManyToOne", "jakarta.persistence.OneToMany",
                "jakarta.persistence.ManyToMany", "jakarta.persistence.OneToOne",
                "javax.persistence.Id", "javax.persistence.Column",
                "javax.persistence.ManyToOne", "javax.persistence.OneToMany",
                "javax.persistence.ManyToMany", "javax.persistence.OneToOne"};
        for (PsiAnnotation annotation : field.getAnnotations()) {
            String annotationQualifiedName = annotation.getQualifiedName();
            for (String jpaAnnotation : jpaAnnotations) {
                if (jpaAnnotation.equals(annotationQualifiedName)) {
                    return true;
                }
            }
        }
        return false;
    }
}

