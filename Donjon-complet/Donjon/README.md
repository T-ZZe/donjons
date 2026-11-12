# Donjon — Paper 26.3

`/donjon` te téléporte dans un monde superplat (créé automatiquement, nommé `donjon`) au cœur d'une salle gothique démesurée
avec trois portails animés : gris (Cendres), rouge (Écarlate), doré.

- `/donjon` : entrer (la salle est construite au premier usage, ~quelques secondes, sans figer le serveur)
- `/donjon quitter` : revenir à l'endroit d'où vous venez
- `/donjon rebuild` (donjon.admin) : reconstruire la salle
- `/donjon info`

Compiler : JDK 25 + Maven, `mvn clean package` → `target/Donjon.jar` (ou GitHub Actions : `.github/workflows/build.yml`).
Configuration : `plugins/Donjon/config.yml` (nom du monde, bordure, noms et messages des portails).
Le code n'a pas été compilé contre l'API Paper 26.3 lors de sa rédaction : seule la syntaxe a été validée.
