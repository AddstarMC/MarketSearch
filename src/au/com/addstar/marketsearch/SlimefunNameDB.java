package au.com.addstar.marketsearch;

import org.bukkit.Material;

import java.io.*;
import java.util.HashMap;

public class SlimefunNameDB {
    public class sfDBItem {
        String sfname;
        Material mat;
        String fullname;

        public sfDBItem(String sfname, Material mat, String fullname) {
            this.sfname = sfname;
            this.mat = mat;
            this.fullname = fullname;
        }
    }

    private HashMap<String, sfDBItem> sfItemDB = new HashMap<>();

    public boolean load(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        int count = 0;
        while (reader.ready()) {
            String line = reader.readLine();
            if (line.startsWith("#"))
                continue;
            String[] parts = line.split(",");
            if (parts.length < 3)
                continue;
            Material mat = Material.getMaterial(parts[1]);
            if (mat == null) {
                System.out.println("[SlimefunNameDB] WARNING: Material \"" + parts[1] + "\" is not valid!");
                continue;
            }
            sfDBItem item = new sfDBItem(parts[0], mat, parts[2]);
            sfItemDB.put(parts[0], item);
            count++;
        }
        System.out.println("[SlimefunNameDB] Added " + count + " search items.");
        return true;
    }

    public boolean load(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            return load(stream);
        }
    }

    public sfDBItem getSFItem(String name) {
        return sfItemDB.getOrDefault(name, null);
    }
}
