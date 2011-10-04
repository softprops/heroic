# heroic

> because sometimes your scala deploy need a hero

Aims to be make deploying scala application as easy as pie. well, as easy as it should be.

## install

1. For starters, you'll need an account on [heroku](https://api.heroku.com/signup)

2. Install the sbt plugin (note not yet published)

Comin' at ya soon

3. deploy

inside sbt

    sbt> hero:auth
    [info] Authenticate with Heroku
    Email: you@somedomain
    Password: ****
    INF: [console logger] dispatch: api.heroku.com POST /login HTTP/1.1
    [info] Wrote credentials to ~/.heroku/credentials
    [info] Heroku requires a public ssh key
    Would you like to associate yours now? [Y/N] yep
    [info] Registering key ~/.ssh/id_rsa.pub
    [info] Registered key
    [success] Total time: 15 s, completed Oct 4, 2011 12:39:48 AM
    sbt> hero:create
    [info] Creating remote Heroku application
    [info] Created app weird-noun-3427
    [info] http://weird-noun-3427.herokuapp.com/ | git@heroku.com:weird-noun-3427.git
    [info] Added git remote heroku
    [success] Total time: 4 s, completed Oct 4, 2011 12:39:57 AM
    sbt> hero:info
    [info] Fetching App info
    [info] === weird-noun-3427
    [info] owner: you@somedomain
    [info] web url: http://weird-noun-3427.herokuapp.com/
    [info] git url: git@heroku.com:weird-noun-3427.git
    [success] Total time: 1 s, completed Oct 4, 2011 12:40:03 AM
    sbt> ...
    sbt> hero:push


To install additional Heroku [addons](http://addons.heroku.com/) you need to [verify](https://api.heroku.com/verify) your account.

## settings

Heroic currently makes assumptions that you have a few external dependencies installed.

You can verify that they are installed locally with the setting

    hero:check-dependencies

### Package settings

    hero:equip        # Prepares project for Heroku deployment
    hero:procfile     # Writes Heroku Procfile to project base directory
    hero:scriptName   # Name of script-file
    hero:scriptFile   # Target process for Heroku web profile key
    hero:script       # Generates script-file
    hero:slugIgnored  # List of items to ignore when transfering application
    hero:slugIgnore   # Generates a Heroku .slugignore file in the base directory
    hero:main-class    # uses sbt's mainClass to task to resolve a main class
    hero:java-options # seq of java options to use in script

heroku uses [git][git] to manage deployements. In order to deploy your changes you need to first commit them.

Heroku also exposes certain runtime properties as env vars. Of note, your Main class should start a server that listens on a port accessible via `System.getenv("PORT").toInt`.

### Client Settings

(More will be added in the in the future)

     hero:check-dependencies # tests to see if all required dependencies are resolvable
     hero:addons     # lists installed addons for app
     hero:addons-available # lists all available addons
     hero:addons-add # installs an addon
     hero:addons-rm  # uninstalls an addon
     hero:create     # shells out to heroku to create a new `stack`. This will add a git remote named heroku to your
                     # git repo
     hero:collaborators # lists all collabs
     hero:collaborators-add # adds a collab
     hero:collaborators-rm  # removes a collab
     hero:conf       # lists remote config (env) vars
     hero:conf-add   # adds a remote config var
     hero:conf-rm    # removes a remote config var
     hero:destroy    # deletes remote heroku application
     hero:domains    # lists heroku domains
     hero:domains-add # adds heroku domain
     hero:domains-rm # removes heroku domain
     hero:push       # pushes application to heroku
     hero:ps         # fetches remote process info
     hero:local-hero # runs local instance of app based on procfile definition
     hero:logs       # fetches remote log info
     hero:addons     # lists current Heroku addons
     hero:addons-add # adds a Heroku addon by name (requires payment validation on site)
     hero:addons-rm  # removes a Heroku addon by name
     hero:addons-upgrade # upgrades a target addon
     hero:info       # renders heroku info
     hero:keys       # lists registered ssh keys
     hero:releases   # listing of all releases
     hero:release-info # provides config info about a target release
     hero:rollback   # rollback to target release
     hero:rename     # changes heroku application name (subdomain)
     hero:maint-on   # enables Heroku maintenance mode
     hero:maint-off  # disables Heroku maintenance mode

* heroku requires a few files in your projects base directly. speficially a pom.xml to hint that the project
  runs on the jvm and optionally a Profile which defines `targets` that you can run through the heroku cli

### Git Settings

To avoid switching contexts, a minimal set of git tasks are provided.
(These will probably be extracted into a separate plugin in the future)

    git:git-add     # adds a space-delimited seq of paths to git, defaults to '.'
    git:git-diff    # shows git diff
    git:git-status  # renders git porcelain status
    git:git-commit  # commits git index with an optional commit msg
    git:exec        # executes arbitrary git command from arguments supplied

### tl;dr

1) run once cmds
   $ git init
   sbt> hero:check-dependencies
   sbt> hero:equip
   sbt> hero:create

2) git commit your code
   sbt> hero:push

3) keep coding

4) goto 2

## dependencies

- [mvn scala plugin](http://scala-tools.org/mvnsites/maven-scala-plugin)
- [heroku gem](https://github.com/heroku/heroku#readme)

## todo

- cache locally what I know doesn't change. (every client call is a remote api call)

Doug Tangren (softprops) 2011

[git]: http://git-scm.com/
