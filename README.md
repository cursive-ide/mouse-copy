# Editor Copy plugin for IntelliJ

Based on the [mouse-copy](http://carcaddar.blogspot.com/2011/01/mouse-copy-for-emacs.html)
plugin for Emacs. 

## How to install:

This plugin is not publicly distributed yet. To install, perform the following steps:
1. Execute `clojure -T:ensure-sdk ensure` in the project root. This will download the IntelliJ SDK.
2. Execute `clojure -T:build package` in the project root. This will build 
   `build/distributions/mouse-copy-0.0.1-2023.1.zip`.
2. Ctrl/Cmd-Shift-A to search for an action. Search for "Install Plugin from Disk..." 
   and execute it. Point the file browser at the zip file above. You shouldn't need
   to restart your IDE if you're using 2020.2 or above.
3. Go to _Preferences | Keymap_, search for "Mouse Copy", double click it and select
   "Add Mouse Shortcut". 
   
Hopefully the plugin should now be working!
