# heroic

> because sometimes your scala deploy need a hero

Aims to be make deploying scala application as easy as pie. well, as easy as it should be.

## install

1. For starters, you'll need an account on [heroku](https://api.heroku.com/signup)
2. Install the  [heroku gem](https://github.com/heroku/heroku#readme)

    gem install heroku

This will install a command line interfaces for interacting with the heroku api *

* In the future I'd like to mirror the same functionality directly via sbt and drop the ruby dependency

2.5. Be _sure_ to log into Heroku via the cmd line

    $ heroku login
    Enter your Heroku credentials.
    Email: you@gmail.com
    Password: ****

3. Install the sbt plugin (note not yet published for sbt 0.11)

Comin' at ya soon

To install additional Heroku [addons](http://addons.heroku.com/) you need to [verify](https://api.heroku.com/verify) your account.

## settings

Heroic currently makes assumptions that you have a few external dependencies installed.

You can verify that they are installed locally with the setting

    hero:check-dependencies

### Package settings

    hero:check-dependencies # tests to see if all required dependencies are resolvable
    hero:main     # uses sbt's mainClass to task to resolve a main class
    hero:script-name # name of the driver script generated
    hero:script   # generates a driver script to run on heroku based on your project's mainClass resolution
    hero:pom      # copies over target pom to pom.xml under base directory including scala mvn plugin
    hero:procfile # generates heroku Procfile
    hero:slug-ignore # generates a default .slugignore file ignoring your src/test and project dirs by default
    hero:slug-ignored # seq of paths to ignore in .slugignore file, defaults to Seq(project, src/test, target)
    hero:jvm-opts # a seq of jvm options to start your app with
    hero:prepare  # prepares project for heroku deployment
    hero:create   # creates an instance of your application on hero (you must commit your code to a git repository first)
    hero:logs     # shows the current remote logs for your application
    hero:ps       # shows your apps remote process info
    hero:push     # pushes an updated copy of your app to Heroku

heroku uses [git][git] to manage deployements. In order to deploy your changes you need to first commit them.

Heroku also exposes certain runtime properties as env vars. Of note, your Main class should start a server that listens on a port accessible via `System.getenv("PORT").toInt`.

### Client Settings

(More will be added in the in the future)

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
   git init
   hero:check-dependencies
   hero:prepare
   hero:create

2) git commit your code
   hero:push

3) keep coding

4) goto 2

## dependencies

- [mvn scala plugin](http://scala-tools.org/mvnsites/maven-scala-plugin)
- [heroku gem](https://github.com/heroku/heroku#readme)

## todo

- cache locally what I know doesn't change. (every client call is a remote api call)
- pass logger into base cmd to debug log all executed cmds
- fork where it makes sense

Doug Tangren (softprops) 2011

[git]: http://git-scm.com/
