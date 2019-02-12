# Jenkins Job DSL

For every configured project it creates Jenkins pipeline jobs for master and every release branch:

Job stages:
* code checkout
* test (with SonarQube analysis for master branch only)
* build (if configured, it triggers job that creates kit)
* waits for Quality Gate results (master only)
* send slack notification on error

Additional steps:
* add gitlab webhook which trigger seed job - it creates job for new release branches
* add gitlab webhook for every branch which triggers corresponding job created by script    
