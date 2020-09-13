# Editor Copy plugin for IntelliJ

Based on the [mouse-copy](http://carcaddar.blogspot.com/2011/01/mouse-copy-for-emacs.html)
plugin for Emacs. 

## How to install:

This plugin is not publicly distributed yet. To install, perform the following steps:
1. Execute `./gradlew buildPlugin` in the project root. This will build 
   `build/distributions/mouse-copy-1.0-SNAPSHOT.zip`.
2. Ctrl/Cmd-Shift-A to search for an action. Search for "Install Plugin from Disk..." 
   and execute it. Point the file browser at the zip file above. You shouldn't need
   to restart your IDE if you're using 2020.2.
3. Go to _Preferences | Keymap_, search for "Mouse Copy", double click it and select
   "Add Mouse Shortcut". 
   
Hopefully the plugin should now be working!
