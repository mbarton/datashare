package org.icij.datashare;


import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.io.FilenameUtils.getExtension;
import static org.fest.assertions.Assertions.assertThat;

public class PluginServiceTest {
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();

    @Test
    public void test_get_plugin_url() throws Exception {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("index.js").toFile().createNewFile();
        assertThat(new PluginService().getPluginUrl(pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin"))).
                isEqualTo("/plugins/my_plugin/index.js");
    }

    @Test
    public void test_get_plugin_url_with_subdirectory() throws Exception {
        pluginFolder.newFolder("target_dir", "my_plugin", "dist").toPath().resolve("main.js").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin").resolve("package.json");
        Files.write(packageJson, asList("{", "\"main\":\"dist/main.js\"", "}"));

        assertThat(new PluginService().getPluginUrl(pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin"))).
                isEqualTo("/plugins/my_plugin/dist/main.js");
    }

    @Test
    public void test_project_filter_json_without_private() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"), asList("{", "\"main\":\"dist/main.js\"", "}"));
        assertThat(new PluginService().projectFilter(packageJson, asList("Toto", "Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_false() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": false",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto","Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_true_without_projects() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": true",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto","Tata"))).isNull();
    }

    @Test
    public void test_project_filter_json_with_private_true_with_projects_ok() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": true,",
                "  \"datashare\": {",
                "    \"projects\": [\"Titi\", \"Tata\"]",
                "   }",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Tata")).toString()).isEqualTo(packageJson.toString());
    }

    @Test
    public void test_project_filter_json_with_private_true_with_projects_nok() throws IOException {
        pluginFolder.newFolder("target_dir", "my_plugin").toPath().resolve("package.json").toFile().createNewFile();
        Path packageJson = pluginFolder.getRoot().toPath().resolve("target_dir").resolve("my_plugin");
        Files.write(packageJson.resolve("package.json"),asList(
                "{",
                "  \"private\": true,",
                "  \"datashare\": {",
                "    \"projects\": [\"Titi\", \"Tata\"]",
                "   }",
                "}"
        ));
        assertThat(new PluginService().projectFilter(packageJson,asList("Toto"))).isNull();
    }

    @Test
    public void test_list_plugins_from_plugins_json_file() throws Exception {
        Set<Plugin> plugins = new PluginService().list();

        assertThat(plugins).hasSize(3);
        assertThat(plugins.stream().map(Plugin::getId).collect(toSet()))
                .containsOnly("my-plugin-foo", "my-plugin-baz", "my-plugin-bar");
    }

    @Test
    public void test_plugin_properties() throws Exception {
        Plugin plugin = new PluginService().list("my-plugin-foo").iterator().next();
        assertThat(plugin.id).isEqualTo("my-plugin-foo");
        assertThat(plugin.description).isEqualTo("description for foo");
        assertThat(plugin.name).isEqualTo("Foo Plugin");
        assertThat(plugin.version).isEqualTo("1.2.3");
        assertThat(plugin.url.toString()).isEqualTo("https://github.com/ICIJ/mypluginfoo/releases/my-plugin-foo.tgz");
    }

    @Test
    public void test_list_plugins_from_plugins_json_file_with_pattern() throws Exception {
        PluginService pluginService = new PluginService();
        assertThat(pluginService.list(".*")).hasSize(3);
        assertThat(pluginService.list(".*foo.*")).hasSize(1);
        assertThat(pluginService.list(".*baz.*")).hasSize(1);
        assertThat(pluginService.list(".*baz.*").iterator().next().id).isEqualTo("my-plugin-baz");
        assertThat(pluginService.list(".*ba.*")).hasSize(2);
    }

    @Test(expected = DeliverableRegistry.UnknownDeliverableException.class)
    public void test_download_unknown_plugin() throws Exception {
        new PluginService(pluginFolder.getRoot().toPath()).downloadAndInstall("unknown-plugin");
    }

    @Test
    public void test_download_and_install_tgz_plugin() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz") + "\"}" +
                "]}").getBytes()));

        Plugin plugin = pluginService.deliverableRegistry.get("my-plugin");
        File tmpFile = plugin.download();
        plugin.install(tmpFile, pluginFolder.getRoot().toPath());

        assertThat(getExtension(tmpFile.getPath())).isEqualTo("tgz");
        assertThat(tmpFile.getName()).startsWith("tmp");
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
        assertThat(tmpFile).doesNotExist();
    }

    @Test
    public void test_download_and_install_zip_plugin() throws Exception {
        new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.zip")+ "\"}" +
                "]}").getBytes())).downloadAndInstall("my-plugin");
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
    }

    @Test
    public void download_and_install_from_url() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath());
        pluginService.downloadAndInstall(ClassLoader.getSystemResource("my-plugin.tgz"));

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
    }

    @Test(expected = FileNotFoundException.class)
    public void test_install_bad_filename() throws Exception {
        new Plugin(null).install(new File("not a file name"), pluginFolder.getRoot().toPath());
    }

    @Test
    public void test_install_from_file() throws Exception {
        File pluginFile = new File(ClassLoader.getSystemResource("my-plugin.tgz").getPath());
        new Plugin(null).install(pluginFile, pluginFolder.getRoot().toPath());

        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("package.json").toFile()).exists();
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").resolve("main.js").toFile()).exists();
        assertThat(pluginFile).exists();
    }

    @Test
    public void test_delete_plugin_by_id() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                        "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz") + "\"}" +
                        "]}").getBytes()));
        pluginService.downloadAndInstall(ClassLoader.getSystemResource("my-plugin.tgz"));

        pluginService.delete("my-plugin");
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).doesNotExist();
    }

    @Test
    public void test_delete_plugin_by_base_directory() throws Exception {
        PluginService pluginService = new PluginService(pluginFolder.getRoot().toPath());
        URL pluginUrl = ClassLoader.getSystemResource("my-plugin.tgz");
        pluginService.downloadAndInstall(pluginUrl);

        pluginService.delete(Paths.get("my-plugin"));
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).doesNotExist();
    }
}
