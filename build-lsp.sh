set -xe

mvn -pl compiler,yakou-maven-plugin,language-server clean package
cd editors/vscode && npm install && npm run compile
cd ../..