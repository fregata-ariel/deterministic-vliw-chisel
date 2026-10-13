# Macのターミナルから作成する

## 前提

GitとGitHub CLIが必要。Homebrewを既に使っていてghが無い場合は次を実行する。

```bash
brew install gh
gh auth login --hostname github.com
gh auth status --hostname github.com
```

選択中のGitHubアカウントを確認する。

```bash
gh api --hostname github.com user --jq .login
```

Gitのcommit用の名前とメールが設定済みであることを確認する。

```bash
git config --get user.name
git config --get user.email
```

未設定の場合、展開したフォルダで先に`git init -b main`し、以下の値を自分の値に置き換えてローカル設定する。スクリプトは、remoteとcommitがまだ無い、このような初期化済みrepoも扱える。

```bash
git config user.name "YOUR NAME"
git config user.email "YOUR VERIFIED OR NOREPLY EMAIL"
```

## 作成

ZIPをダウンロードし、まだ同名フォルダが無い場所へ展開する。例えばDownloadsにZIPを保存した場合：

```bash
cd ~/Downloads
unzip deterministic-vliw-chisel.zip
cd deterministic-vliw-chisel
bash scripts/create-github-repo.sh
```

新規リポジトリを、選択中アカウントの`deterministic-vliw-chisel`としてprivateで作成する。初期ファイルをcommitし、mainをpushする。別名ならスクリプトの第1引数に指定する。

スクリプトは他のrepo内、既存commitのあるrepo、既存remoteのあるrepo、同名GitHub repoがある場合に停止する。追加commitや既存repoへのpushは通常のGit手順で行う。

GitHub作成の中心コマンドは次のとおり。スクリプトはその前にGit初期化・commitを行い、HTTPS認証をこの実行に限定して設定する。

```bash
gh repo create OWNER/REPO --private --source=. --remote=origin --push \
  --description "Compiler-scheduled, fixed-cycle VLIW microengine in Chisel"
```

## 確認

```bash
git status --short
git log -1 --oneline
gh repo view --json nameWithOwner,isPrivate,url
git ls-remote origin refs/heads/main
```

## 途中で失敗した場合

ローカルcommitのあとにGitHub APIやpushが失敗した場合、ファイルとcommitは保持される。スクリプトを再実行するのではなく、状態を確認する。

```bash
git log -1 --oneline
git remote -v
```

GitHub repoとoriginが作成済みなら、認証を確認してHTTPSで再pushする。

```bash
gh auth status --hostname github.com
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push -u origin main
```

GitHub repo未作成なら、作成コマンドを手動実行する。repoは存在するがoriginが無ければ、正しいrepo URLを確認して`git remote add origin https://github.com/OWNER/REPO.git`した後にpushする。失敗時に既存repoを削除したりforce pushしたりする必要はない。

## Chiselのセットアップ

この初期パッケージには`build.sbt`やRTLをまだ含めない。Phase 1着手時に公式`chisel-template`の動作確認済みcommitを参考に`hw/`へビルドを導入する。採用したChisel、Scala、plugin、JDK、ビルドツール、Verilatorのバージョンを同時に記録する。
