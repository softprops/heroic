# heroic

> because sometimes your scala deploy need a hero

An sbt interface for remotely administering and deploying [heroku][heroku] hosted applications

## install

1. For starters, you'll need an account on [heroku][signup]

2. Install the sbt plugin (note not yet published)

Comin' at ya soon

3. Deploy

Inside sbt you will need to first authenticate with heroku, only once (you can undo this later)

    sbt> hero::authenticate
    [info] Authenticate with Heroku
    Enter your API key (from https://dashboard.heroku.com/account): ********************************

Generate some heroku specifics for deployment using your apps `Main` class
    
    sbt> hero::equip
    
Create remote application

    sbt> hero::create
    
Deploy to it (note you can do this from the command line with `git push heroku master`)
    
    sbt> hero::deploy

To install additional Heroku [addons](http://addons.heroku.com/) you need to [verify](https://api.heroku.com/verify) your account.

## settings


### Package settings

    hero::equip        # Prepares project for Heroku deployment
    hero::procfile     # Writes Heroku Procfile to project base directory
    hero::script-name   # Name of script-file
    hero::script-file   # Target process for Heroku web profile key
    hero::script       # Generates script-file
    hero::slug-ignored  # List of items to ignore when transfering application
    hero::slug-ignore   # Generates a Heroku .slugignore file in the base directory
    hero::main-class    # uses sbt's mainClass to task to resolve a main class
    hero::java-options # seq of java options to use in script

heroku uses [git][git] to manage deployements. In order to deploy your changes you need to first commit them.

Heroku also exposes certain runtime properties as env vars. Of note, your Main class should start a server that listens on a port accessible via `System.getenv("PORT").toInt`.

### Client Settings

(More will be added in the in the future)
Note: This plugin supports multiple deployment environments (i.e. multiple Heroku remotes) The default Heroku env is named heroku. A common pattern for application development is to publish to a staging env before deploying to production. You can create a named Heroku env with `hero-create <env>`. The arguments of the keys below will default the the heroku env. Simply use your own env name when modifying that env (`hero-conf-add <env> <key> <val>`)

     hero::check-dependencies # tests to see if all required dependencies are resolvable
     hero::addons     # lists installed addons for app
     hero::addons-available # lists all available addons
     hero::addons-add # installs an addon
     hero::addons-rm  # uninstalls an addon
     hero::create     # shells out to heroku to create a new `stack`. This will add a git remote named heroku to your
                     # git repo
     hero::collaborators # lists all collabs
     hero::collaborators-add # adds a collab
     hero::collaborators-rm  # removes a collab
     hero::config       # lists remote config (env) vars
     hero::config-add   # adds a remote config var
     hero::config-rm    # removes a remote config var
     hero::destroy    # deletes remote heroku application
     hero::domains    # lists heroku domains
     hero::domains-add # adds heroku domain
     hero::domains-rm # removes heroku domain
     hero::deploy       # pushes application to heroku
     hero::ps         # fetches remote process info
     hero::local-hero # runs local instance of app based on procfile definition
     hero::logs       # fetches remote log info
     hero::addons     # lists current Heroku addons
     hero::addons-add # adds a Heroku addon by name (requires payment validation on site)
     hero::addons-rm  # removes a Heroku addon by name
     hero::addons-upgrade # upgrades a target addon
     hero::info       # renders heroku info
     hero::keys       # lists registered ssh keys
     hero::releases   # listing of all releases
     hero::release-info # provides config info about a target release
     hero::rollback   # rollback to target release
     hero::rename     # changes heroku application name (subdomain)
     hero::maint-on   # enables Heroku maintenance mode
     hero::maint-off  # disables Heroku maintenance mode

* heroku requires a few files in your projects base directly. speficially a `build.properties` sbt config file under the `project` directory or your projects base

### tl;dr

1) run once cmds
   $ git init
   sbt> hero::equip
   sbt> hero::create

2) git commit your code
   sbt> hero::deploy

3) keep coding

4) goto 2

## dependencies

`git`

## todo

- cache locally what I know doesn't change. (every client call is a remote api call)

Doug Tangren (softprops) 2011-2012

[git]: http://git-scm.com/
[heroku]: http://www.heroku.com/
[signup]: https://api.heroku.com/signup
