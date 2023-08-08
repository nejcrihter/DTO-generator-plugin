package si.petrol.dtogenerator;
/* created: 7. 08. 2023
 * by: Nejc Rihter mailto:nejc.rihter@petrol.si
 * for: PETROL d.d.
 */


import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

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
            System.out.println("psiClass null");
            return false;
        }
        PsiAnnotation entityAnnotation = psiClass.getAnnotation("jakarta.persistence.Entity");
        System.out.println("Class is JPA entity: " + entityAnnotation);
        return entityAnnotation != null;
    }

    private void generateNewDtoForEntity(PsiClass entityClass) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();

        // Create ReturnDTO class name
        String newDTOClassName = "New" + entityClass.getName() + "DTO";

        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        //Get the service.classname directory
        PsiDirectory classDirectory = getClassDirectory(entityClass, directory);

        // Check if ReturnDTO class already exists
        PsiFile existingFile = classDirectory.findFile(newDTOClassName + ".java");
        if (existingFile != null) {
            System.out.println("NewDTO class already exists: " + newDTOClassName);
            return; // Exit the method if ReturnDTO class already exists
        }

        // Create ReturnDTO class
        PsiClass newDTOClass = factory.createClass(newDTOClassName);

        // Create a default constructor for ReturnDTO class
        PsiMethod defaultConstructor = factory.createConstructor();
        newDTOClass.add(defaultConstructor);

        // Create a StringBuilder to hold the constructor parameters and body
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();

        for (PsiField field : entityClass.getFields()) {
            if (hasJpaAnnotations(field)) {
                PsiType fieldType;
                String fieldName;
                boolean isNotNull = false;

                PsiAnnotation columnAnnotation = field.getAnnotation("jakarta.persistence.Column");
                if (columnAnnotation != null) {
                    PsiAnnotationMemberValue nullableValue = columnAnnotation.findAttributeValue("nullable");
                    if (nullableValue != null && "false".equals(nullableValue.getText())) {
                        isNotNull = true;
                    }
                }

                if (isForeignKey(field)) {
                    fieldType = factory.createTypeByFQClassName("java.lang.String", entityClass.getResolveScope());
                    fieldName = field.getName() + "Id";
                } else {
                    fieldType = field.getType();
                    fieldName = field.getName();
                }

                // Add field to ReturnDTO class with annotations
                PsiField newField = factory.createField(fieldName, fieldType);

                if (isNotNull) {
                    if (fieldType.getCanonicalText().equals("java.lang.String")) {
                        Objects.requireNonNull(newField.getModifierList()).addAnnotation("NotBlank");
                    } else {
                        Objects.requireNonNull(newField.getModifierList()).addAnnotation("NotNull");
                    }
                }
                newDTOClass.add(newField);

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
                                "() { return " + fieldName + "; }", newDTOClass);
                newDTOClass.add(getter);

                // Add setter for the field
                PsiMethod setter = factory.createMethodFromText(
                        "public void set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "(" + fieldType.getPresentableText() + " " + fieldName + ") { this." +
                                fieldName + " = " + fieldName + "; }", newDTOClass);
                newDTOClass.add(setter);
            }
        }

        // Create and add constructor with parameters to DTO class
        PsiMethod constructor = factory.createMethodFromText(
                "public " + newDTOClass.getName() + "(" + constructorParams.toString() + ") { " +
                        constructorBody.toString() +
                        " }", newDTOClass);
        newDTOClass.add(constructor);

        // Import the annotations
        PsiJavaFile javaFile = (PsiJavaFile) newDTOClass.getContainingFile();
        Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("jakarta.validation.constraints"));

        // Use WriteCommandAction to make modifications
        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Add ReturnDTO class to the same directory as the entity
            classDirectory.add(newDTOClass);
        });

        // Create mapper directory and MapStruct mapper
        createMapperForEntity(entityClass, newDTOClass);

    }

    private void generateUpdateDtoForEntity(PsiClass entityClass) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();

        // Create ReturnDTO class name
        String updateDTOClassName = entityClass.getName() + "UpdateDTO";

        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        //Get the service.classname directory
        PsiDirectory classDirectory = getClassDirectory(entityClass, directory);

        // Check if ReturnDTO class already exists
        PsiFile existingFile = classDirectory.findFile(updateDTOClassName + ".java");
        if (existingFile != null) {
            System.out.println("UpdateDTO class already exists: " + updateDTOClassName);
            return; // Exit the method if ReturnDTO class already exists
        }

        // Create ReturnDTO class
        PsiClass updateDTOClass = factory.createClass(updateDTOClassName);

        // Create a default constructor for ReturnDTO class
        PsiMethod defaultConstructor = factory.createConstructor();
        updateDTOClass.add(defaultConstructor);

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
                // Add field to ReturnDTO class with annotations
                PsiField newField = factory.createField(fieldName, fieldType);
                updateDTOClass.add(newField);

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
                                "() { return " + fieldName + "; }", updateDTOClass);
                updateDTOClass.add(getter);

                // Add setter for the field
                PsiMethod setter = factory.createMethodFromText(
                        "public void set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "(" + fieldType.getPresentableText() + " " + fieldName + ") { this." +
                                fieldName + " = " + fieldName + "; }", updateDTOClass);
                updateDTOClass.add(setter);
            }
        }

        // Create and add constructor with parameters to DTO class
        PsiMethod constructor = factory.createMethodFromText(
                "public " + updateDTOClass.getName() + "(" + constructorParams.toString() + ") { " +
                        constructorBody.toString() +
                        " }", updateDTOClass);
        updateDTOClass.add(constructor);

        // Use WriteCommandAction to make modifications
        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Add ReturnDTO class to the same directory as the entity
            classDirectory.add(updateDTOClass);
        });

        // Create mapper directory and MapStruct mapper
        createMapperForEntity(entityClass, updateDTOClass);
    }

    private void generatePutDtoForEntity(PsiClass entityClass) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();

        // Create ReturnDTO class name
        String putDTOClassName = entityClass.getName() + "PutDTO";

        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        //Get the service.classname directory
        PsiDirectory classDirectory = getClassDirectory(entityClass, directory);

        // Check if ReturnDTO class already exists
        PsiFile existingFile = classDirectory.findFile(putDTOClassName + ".java");
        if (existingFile != null) {
            System.out.println("PutDTO class already exists: " + putDTOClassName);
            return; // Exit the method if ReturnDTO class already exists
        }

        // Create ReturnDTO class
        PsiClass putDTOClass = factory.createClass(putDTOClassName);


        // Create a default constructor for ReturnDTO class
        PsiMethod defaultConstructor = factory.createConstructor();
        putDTOClass.add(defaultConstructor);

        // Create a StringBuilder to hold the constructor parameters and body
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();

        for (PsiField field : entityClass.getFields()) {
            if (hasJpaAnnotations(field)) {
                PsiType fieldType;
                String fieldName;
                boolean isNotNull = false;

                PsiAnnotation columnAnnotation = field.getAnnotation("jakarta.persistence.Column");
                if (columnAnnotation != null) {
                    PsiAnnotationMemberValue nullableValue = columnAnnotation.findAttributeValue("nullable");
                    if (nullableValue != null && "false".equals(nullableValue.getText())) {
                        isNotNull = true;
                    }
                }

                if (isForeignKey(field)) {
                    fieldType = factory.createTypeByFQClassName("java.lang.String", entityClass.getResolveScope());
                    fieldName = field.getName() + "Id";
                } else {
                    fieldType = field.getType();
                    fieldName = field.getName();
                }

                // Add field to ReturnDTO class with annotations
                PsiField newField = factory.createField(fieldName, fieldType);

                if (isNotNull) {
                    if (fieldType.getCanonicalText().equals("java.lang.String")) {
                        Objects.requireNonNull(newField.getModifierList()).addAnnotation("NotBlank");
                    } else {
                        Objects.requireNonNull(newField.getModifierList()).addAnnotation("NotNull");
                    }
                }
                putDTOClass.add(newField);

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
                                "() { return " + fieldName + "; }", putDTOClass);
                putDTOClass.add(getter);

                // Add setter for the field
                PsiMethod setter = factory.createMethodFromText(
                        "public void set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "(" + fieldType.getPresentableText() + " " + fieldName + ") { this." +
                                fieldName + " = " + fieldName + "; }", putDTOClass);
                putDTOClass.add(setter);
            }
        }

        // Create and add constructor with parameters to DTO class
        PsiMethod constructor = factory.createMethodFromText(
                "public " + putDTOClass.getName() + "(" + constructorParams.toString() + ") { " +
                        constructorBody.toString() +
                        " }", putDTOClass);
        putDTOClass.add(constructor);


        // Import the annotations
        PsiJavaFile javaFile = (PsiJavaFile) putDTOClass.getContainingFile();
        Objects.requireNonNull(javaFile.getImportList()).add(factory.createImportStatementOnDemand("jakarta.validation.constraints"));

        // Use WriteCommandAction to make modifications
        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Add ReturnDTO class to the same directory as the entity
            classDirectory.add(putDTOClass);
        });

        // Create mapper directory and MapStruct mapper
        createMapperForEntity(entityClass, putDTOClass);
    }

    private void generateReturnDtoForEntity(PsiClass entityClass) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(entityClass.getProject()).getElementFactory();

        // Create ReturnDTO class name
        String returnDtoClassName = entityClass.getName() + "ReturnDTO";

        PsiDirectory directory = entityClass.getContainingFile().getContainingDirectory();

        //Get the service.classname directory
        PsiDirectory classDirectory = getClassDirectory(entityClass, directory);

        // Check if ReturnDTO class already exists
        PsiFile existingFile = classDirectory.findFile(returnDtoClassName + ".java");
        if (existingFile != null) {
            System.out.println("ReturnDTO class already exists: " + returnDtoClassName);
            return; // Exit the method if ReturnDTO class already exists
        }

        // Create ReturnDTO class
        PsiClass returnDtoClass = factory.createClass(returnDtoClassName);

        // Create a default constructor for ReturnDTO class
        PsiMethod defaultConstructor = factory.createConstructor();
        returnDtoClass.add(defaultConstructor);

        // Create a StringBuilder to hold the constructor parameters and body
        StringBuilder constructorParams = new StringBuilder();
        StringBuilder constructorBody = new StringBuilder();

        for (PsiField field : entityClass.getFields()) {
            if (hasJpaAnnotations(field)) {
                PsiType fieldType;
                String fieldName;
                boolean isNotNull = false;

                PsiAnnotation columnAnnotation = field.getAnnotation("jakarta.persistence.Column");
                if (columnAnnotation != null) {
                    PsiAnnotationMemberValue nullableValue = columnAnnotation.findAttributeValue("nullable");
                    if (nullableValue != null && "false".equals(nullableValue.getText())) {
                        isNotNull = true;
                    }
                }

                if (isForeignKey(field)) {
                    fieldType = factory.createTypeByFQClassName(field.getType().getPresentableText() + "ReturnDTO", entityClass.getResolveScope());
                    fieldName = field.getName() + "ReturnDTO";
                } else {
                    fieldType = field.getType();
                    fieldName = field.getName();
                }

                // Add field to ReturnDTO class with annotations
                PsiField newField = factory.createField(fieldName, fieldType);

                if (isNotNull) {
                    if (fieldType.getCanonicalText().equals("java.lang.String")) {
                        newField.getModifierList().addAnnotation("NotBlank");
                    } else {
                        newField.getModifierList().addAnnotation("NotNull");
                    }
                }
                returnDtoClass.add(newField);

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
                                "() { return " + fieldName + "; }", returnDtoClass);
                returnDtoClass.add(getter);

                // Add setter for the field
                PsiMethod setter = factory.createMethodFromText(
                        "public void set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) +
                                "(" + fieldType.getPresentableText() + " " + fieldName + ") { this." +
                                fieldName + " = " + fieldName + "; }", returnDtoClass);
                returnDtoClass.add(setter);
            }
        }

        // Create and add constructor with parameters to DTO class
        PsiMethod constructor = factory.createMethodFromText(
                "public " + returnDtoClass.getName() + "(" + constructorParams.toString() + ") { " +
                        constructorBody.toString() +
                        " }", returnDtoClass);
        returnDtoClass.add(constructor);

        // Import the annotations
        PsiJavaFile javaFile = (PsiJavaFile) returnDtoClass.getContainingFile();
        javaFile.getImportList().add(factory.createImportStatementOnDemand("jakarta.validation.constraints"));

        // Use WriteCommandAction to make modifications
        WriteCommandAction.runWriteCommandAction(entityClass.getProject(), () -> {
            // Add ReturnDTO class to the same directory as the entity
            classDirectory.add(returnDtoClass);
        });
        // Create mapper directory and MapStruct mapper
        createMapperForEntity(entityClass, returnDtoClass);
    }

    private static PsiDirectory getClassDirectory(PsiClass entityClass, PsiDirectory directory) {
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

    public static String lowercaseFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str; // return the original string if it's null or empty
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
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
                mapperDirectory = parentDirectory.findSubdirectory("mapper");
                if (mapperDirectory == null) {
                    mapperDirectory = parentDirectory.createSubdirectory("mapper");
                }
            }

            String mapperName = entityClass.getName() + "Mapper";
            PsiFile existingMapper = mapperDirectory.findFile(mapperName + ".java");
            String mappingMethods ="    " + dtoClass.getName() + " to" + dtoClass.getName() + "(" + entityClass.getName() + " " + Objects.requireNonNull(lowercaseFirstLetter(entityClass.getName())) + ");\n" +
                    "    List<" + dtoClass.getName() + ">" + " to" + dtoClass.getName() + "(List<" + entityClass.getName() + "> " + lowercaseFirstLetter(entityClass.getName()) + ");\n";

            if (existingMapper == null) {
                String mapperText = "import org.mapstruct.Mapper;\n" +
                        "import org.mapstruct.Mapping;\n" +
                        "import org.mapstruct.factory.Mappers;\n\n" +
                        "@Mapper(componentModel = \"jakarta\")\n" +
                        "public interface " + mapperName + " {\n" +
                        mappingMethods +
                        "}";

                PsiFileFactory fileFactory = PsiFileFactory.getInstance(entityClass.getProject());
                PsiFile mapperFile = fileFactory.createFileFromText(mapperName + ".java", JavaFileType.INSTANCE, mapperText);

                mapperDirectory.add(mapperFile);

            } else {
                // If the mapper file already exists, append the new mapping methods to it
                PsiClass existingMapperClass = ((PsiJavaFile) existingMapper).getClasses()[0];
                if (existingMapperClass != null) {
                    PsiMethod method1 = null;
                    PsiMethod method2 = null;

                    if (Objects.requireNonNull(dtoClass.getName()).contains("UpdateDTO")) {
                        method1 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)"
                                + dtoClass.getName() + " from" + dtoClass.getName() + "(@MappingTarget " + entityClass.getName() + " " + lowercaseFirstLetter(entityClass.getName()) + ", " + dtoClass.getName() + " " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                        method2 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)" +
                                "List<" + dtoClass.getName() + ">" + " to" + dtoClass.getName() + "(@MappingTarget  List<" + entityClass.getName() + "> " + lowercaseFirstLetter(entityClass.getName()) + ", " + dtoClass.getName() + " " + lowercaseFirstLetter(dtoClass.getName()) + ");", existingMapperClass);
                    } else {
                        method1 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText(dtoClass.getName() + " to" + dtoClass.getName() + "(" + entityClass.getName() + " " + lowercaseFirstLetter(entityClass.getName()) + ");", existingMapperClass);
                        method2 = JavaPsiFacade.getElementFactory(entityClass.getProject()).createMethodFromText("List<" + dtoClass.getName() + ">" + " to" + dtoClass.getName() + "(List<" + entityClass.getName() + "> " + lowercaseFirstLetter(entityClass.getName()) + ");", existingMapperClass);
                    }
                    existingMapperClass.add(method1);
                    existingMapperClass.add(method2);

                    // Import the annotations
                    PsiJavaFile javaFile = (PsiJavaFile) existingMapperClass.getContainingFile();
                    javaFile.getImportList().add(factory.createImportStatementOnDemand("org.mapstruct"));
                }
            }
        });
    }


    // Helper method to determine if a field represents a foreign key
    private boolean isForeignKey(PsiField field) {
        // This is a basic check based on JPA annotations. Adjust as needed for your project.
        return Arrays.stream(field.getAnnotations())
                .anyMatch(annotation -> annotation.getQualifiedName().equals("jakarta.persistence.ManyToOne") ||
                        annotation.getQualifiedName().equals("jakarta.persistence.OneToOne"));
    }


    private boolean hasJpaAnnotations(PsiField field) {
        String[] jpaAnnotations = {"jakarta.persistence.Id", "jakarta.persistence.Column",
                "jakarta.persistence.ManyToOne", "jakarta.persistence.OneToMany",
                "jakarta.persistence.ManyToMany", "jakarta.persistence.OneToOne"};
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

