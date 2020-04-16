package me.jackscode.timedfly.handlers;

import me.jackscode.timedfly.TimedFly;
import me.jackscode.timedfly.api.Module;
import me.jackscode.timedfly.api.ModuleDescription;
import me.jackscode.timedfly.exceptions.CommandException;
import me.jackscode.timedfly.exceptions.ModuleException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ModuleHandler {

    private final List<Module> modules;
    private final CommandHandler commandHandler;
    private final TimedFly plugin;

    public ModuleHandler(@NotNull CommandHandler commandHandler, TimedFly plugin) {
        this.commandHandler = commandHandler;
        this.modules = new ArrayList<>();
        this.plugin = plugin;
    }

    public void enableModules(Path path) {
        try {
            Files.list(path)
                    .map(Path::toFile)
                    .forEach(this::enableModule);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Module enableModule(File fileModule) {
        String filePath = fileModule.getPath();
        try {
            if (!fileModule.exists()) {
                throw new ModuleException(fileModule.getPath() + " does not exist!");
            }

            System.out.println("Attempting to load module: " + filePath);

            // Prepare to load class
            URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{fileModule.toURI().toURL()},
                    this.getClass().getClassLoader()
            );

            // Get module.yml file
            InputStream inputStream = classLoader.getResourceAsStream("module.yml");

            // module.yml must exist
            if (inputStream == null) {
                throw new ModuleException("There is no module.yml file on the module " + filePath);
            }

            // Read contents of module.yml file
            InputStreamReader streamReader = new InputStreamReader(inputStream);
            BufferedReader reader = new BufferedReader(streamReader);

            // Load yml into a YamlConfiguration
            YamlConfiguration moduleConfig = new YamlConfiguration();
            moduleConfig.load(reader);

            // Create an instance of the module description and add the values
            ModuleDescription moduleDescription = this.populateModuleDescription(moduleConfig, filePath);

            // Check if the module already exists
            boolean exists = modules.stream()
                    .anyMatch(module -> module
                            .getModuleDescription()
                            .getName()
                            .equals(moduleDescription.getName())
                    );
            if (exists) {
                throw new ModuleException("There already exist a module with the name of " + filePath);
            }

            // Path to main class
            String main = moduleDescription.getMain();

            // Get class to load
            Class<?> clazz = Class.forName(main, true, classLoader);

            // Load class constructor
            Constructor<?> constructor = clazz.getConstructor();

            // Make new instance of the module
            Object instance = constructor.newInstance();

            // Class must extend Module abstract class
            if (!(instance instanceof Module)) {
                throw new ModuleException(main + " must implement Module");
            }

            Module module = (Module) instance;

            // Set the values for all needed fields.
            this.setFields(module, "moduleDescription", moduleDescription);
            this.setFields(module, "commandHandler", commandHandler);
            this.setFields(module, "moduleHandler", this);
            this.setFields(module, "plugin", this.plugin);

            // Close classloader because we dont need it any more.

            System.out.println("Module " + filePath + " has been loaded");
            modules.add(module);
            module.onModuleEnable();
            return module;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Could not load module: " + filePath);
            return null;
        }
    }

    public void disableAllModules() {
        modules.forEach(module -> {
            module.onModuleDisable();
            module.getCommandList().forEach(command -> {
                try {
                    commandHandler.unregister(command);
                } catch (CommandException e) {
                    e.printStackTrace();
                }
            });
        });
        System.out.println("All modules had been disabled.");
    }

    public void disableModule(Module module) {
        module.onModuleDisable();
        module.getCommandList().forEach(command -> {
            try {
                commandHandler.unregister(command);
            } catch (CommandException e) {
                e.printStackTrace();
            }
        });

        System.out.println("Module disabled: " + module.getModuleDescription().getName());
        modules.remove(module);
    }

    private ModuleDescription populateModuleDescription(FileConfiguration moduleConfig, String filePath) throws ModuleException {
        // Get all the values from module.yml file
        String main = moduleConfig.getString("main");
        String name = moduleConfig.getString("name");
        String description = moduleConfig.getString("description");
        String version = moduleConfig.getString("version");
        List<String> authors = moduleConfig.getStringList("authors");

        String moduleException = "There is no '%s' section on module.yml of " + filePath;

        // Check to see if main, name, version is in the module.yml file
        if (main == null) {
            throw new ModuleException(String.format(moduleException, "main"));
        } else if (name == null) {
            throw new ModuleException(String.format(moduleException, "name"));
        } else if (version == null) {
            throw new ModuleException(String.format(moduleException, "version"));
        }

        // Set default value for module description.
        if (description == null) {
            description = "No description provided";
        }

        return new ModuleDescription(main, name, description, version, authors);
    }

    private void setFields(Module module, String fieldName, Object value) throws NoSuchFieldException, IllegalAccessException {
        // Get the module's description field to be populated
        Field field = module.getClass().getSuperclass().getDeclaredField(fieldName);

        // Set private variable accessible to be able to change it
        field.setAccessible(true);

        // Populate module's description field
        field.set(module, value);
    }

    public List<Module> getModules() {
        return modules;
    }
}
